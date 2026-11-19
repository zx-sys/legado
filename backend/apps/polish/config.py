from django.conf import settings
from django.core.cache import cache

from mysystem.models import SystemConfig

CACHE_KEY = "polish:daily_budget"


def get_daily_budget_cny() -> float:
    cached = cache.get(CACHE_KEY)
    if cached is not None:
        return float(cached)
    row = (
        SystemConfig.objects.filter(key="polish_daily_budget")
        .values_list("value", flat=True)
        .first()
    )
    default = float(getattr(settings, "POLISH_DAILY_BUDGET_CNY", 2.0))
    try:
        value = float(row) if row not in (None, "") else default
    except (TypeError, ValueError):
        value = default
    cache.set(CACHE_KEY, value, 30)
    return value
