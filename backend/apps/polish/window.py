from datetime import datetime, time
from zoneinfo import ZoneInfo

from apps.polish.holidays import get_polish_holidays

TZ = ZoneInfo("Asia/Shanghai")
PEAK_WINDOWS = ((time(9, 0), time(12, 0)), (time(14, 0), time(18, 0)))


def is_idle_window(dt: datetime | None = None, holidays=None) -> bool:
    """空闲时段 = 周一至五 9-12、14-18 之外 + 周末/节假日全天。"""
    dt = dt or datetime.now(TZ)
    dt = dt.replace(tzinfo=TZ) if dt.tzinfo is None else dt.astimezone(TZ)
    if holidays is None:
        holidays = get_polish_holidays()
    if dt.date().isoformat() in set(holidays):
        return True
    if dt.weekday() >= 5:
        return True
    now = dt.time()
    return not any(start <= now < end for start, end in PEAK_WINDOWS)

