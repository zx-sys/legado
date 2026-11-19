import logging
import time
from datetime import date, datetime
from zoneinfo import ZoneInfo

import requests
from django.conf import settings
from django.core.cache import cache

from mysystem.models import SystemConfig

logger = logging.getLogger(__name__)

HOLIDAY_CONFIG_KEY = "polish_holidays"
HOLIDAY_CACHE_KEY = "polish:holidays"
DEFAULT_HOLIDAY_URL = (
    "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/{year}.json"
)
TZ = ZoneInfo("Asia/Shanghai")


class HolidayDataError(ValueError):
    pass


def parse_holiday_payload(payload: dict, year: int) -> set[str]:
    days = payload.get("days") if isinstance(payload, dict) else None
    if not isinstance(days, list):
        raise HolidayDataError("days 缺失或不是数组")
    result = set()
    prefix = f"{year}-"
    for item in days:
        if not isinstance(item, dict) or not item.get("isOffDay"):
            continue
        raw = str(item.get("date") or "")
        if not raw.startswith(prefix):
            continue
        try:
            parsed = date.fromisoformat(raw)
        except ValueError:
            continue
        if parsed.year != year:
            continue
        result.add(parsed.isoformat())
    if len(result) < 5:
        raise HolidayDataError(f"{year} 节假日数量异常: {len(result)}")
    return result


def _config_value() -> str | None:
    try:
        return (
            SystemConfig.objects.filter(key=HOLIDAY_CONFIG_KEY)
            .values_list("value", flat=True)
            .first()
        )
    except Exception:
        logger.exception("读取 polish_holidays 配置失败")
        return None


def _valid_dates(values) -> set[str]:
    result = set()
    for item in values:
        if not isinstance(item, str):
            continue
        text = item.strip()
        if not text:
            continue
        try:
            date.fromisoformat(text)
        except ValueError:
            continue
        result.add(text)
    return result


def get_polish_holidays() -> set[str]:
    cached = cache.get(HOLIDAY_CACHE_KEY)
    if cached is not None:
        return _valid_dates(cached)
    value = _config_value()
    if value:
        dates = _valid_dates(value.split(","))
    else:
        dates = _valid_dates(getattr(settings, "POLISH_HOLIDAYS", ()) or ())
    cache.set(HOLIDAY_CACHE_KEY, dates, 300)
    return dates


def _fetch_year(year: int) -> dict:
    template = getattr(settings, "POLISH_HOLIDAY_URL", DEFAULT_HOLIDAY_URL)
    url = template.format(year=year)
    last_error = None
    for attempt in range(3):
        try:
            resp = requests.get(url, timeout=(10, 30))
            resp.raise_for_status()
            return resp.json()
        except Exception as exc:
            last_error = exc
            if attempt < 2:
                time.sleep(2**attempt)
    raise last_error or RuntimeError(f"获取 {year} 节假日失败")


def refresh_holidays(years: list[int] | None = None, fetcher=None) -> dict:
    now = datetime.now(TZ)
    target_years = years or [now.year, now.year + 1]
    fetch = fetcher or _fetch_year
    existing = get_polish_holidays()
    existing_by_year: dict[str, set[str]] = {}
    for item in existing:
        existing_by_year.setdefault(item[:4], set()).add(item)

    merged: set[str] = set()
    ok_years: list[int] = []
    errors: list[str] = []
    for year in target_years:
        try:
            merged |= parse_holiday_payload(fetch(year), year)
            ok_years.append(year)
        except Exception as exc:
            errors.append(f"{year}: {exc}")
            merged |= existing_by_year.get(str(year), set())

    if not ok_years:
        raise RuntimeError("节假日刷新失败: " + "; ".join(errors))

    value = ",".join(sorted(merged))
    SystemConfig.objects.update_or_create(
        parent=None,
        key=HOLIDAY_CONFIG_KEY,
        defaults={
            "title": "AI 清洗节假日",
            "value": value,
            "status": True,
            "sort": 0,
            "form_item_type": 3,
        },
    )
    cache.delete(HOLIDAY_CACHE_KEY)
    return {"years": ok_years, "count": len(merged), "errors": errors}
