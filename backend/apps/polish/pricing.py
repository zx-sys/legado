from django.conf import settings

DEFAULT_PRICES = {
    "flash": {"input_hit": 0.02, "input_miss": 1.0, "output": 4.0},
    "v4-pro": {"input_hit": 0.15, "input_miss": 4.5, "output": 13.5},
}


def estimate_tokens(text: str) -> int:
    # DeepSeek 中文约 1 token ≈ 1.6 字
    return max(1, int(len(text) / 1.6))


def _prices(model: str) -> dict:
    table = getattr(settings, "POLISH_PRICES", DEFAULT_PRICES)
    return table.get(model, table["flash"])


def cost_micros(usage: dict, idle: bool, model: str = "flash") -> int:
    prices = _prices(model)
    hit = int(usage.get("prompt_cache_hit_tokens", 0) or 0)
    miss = int(
        usage.get("prompt_cache_miss_tokens")
        or max(0, int(usage.get("prompt_tokens", 0) or 0) - hit)
    )
    out = int(usage.get("completion_tokens", 0) or 0)
    yuan = (
        hit / 1_000_000 * prices["input_hit"]
        + miss / 1_000_000 * prices["input_miss"]
        + out / 1_000_000 * prices["output"]
    )
    if not idle:
        yuan *= 2
    return int(round(yuan * 1_000_000))
