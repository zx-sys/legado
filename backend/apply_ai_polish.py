#!/usr/bin/env python3
"""Idempotently enable the AI polish app on an existing Legado backend.

Only edits:
  - application/settings.py: add 'apps.polish' to INSTALLED_APPS
  - config.py: append the polish config block if missing

It never touches config_local.py or SECRET_KEY.
"""
from pathlib import Path

BASE = Path(__file__).resolve().parent
SETTINGS = BASE / "application" / "settings.py"
CONFIG = BASE / "config.py"

CONFIG_BLOCK = """
# ================================================= #
# ************** DeepSeek 正文清洗  ************** #
# ================================================= #
DEEPSEEK_API_KEY = os.environ.get("LEGADO_DEEPSEEK_API_KEY", "")
DEEPSEEK_BASE_URL = os.environ.get("LEGADO_DEEPSEEK_BASE_URL", "https://api.deepseek.com")
POLISH_MODEL = os.environ.get("LEGADO_POLISH_MODEL", "deepseek-flash")
POLISH_DAILY_BUDGET_CNY = float(os.environ.get("LEGADO_POLISH_DAILY_BUDGET_CNY", "2"))
POLISH_BOOK_BUDGET_CNY = float(os.environ.get("LEGADO_POLISH_BOOK_BUDGET_CNY", "0"))
POLISH_HOLIDAYS = tuple(
    x.strip() for x in os.environ.get("LEGADO_POLISH_HOLIDAYS", "").split(",") if x.strip()
)
POLISH_HOLIDAY_URL = os.environ.get(
    "LEGADO_POLISH_HOLIDAY_URL",
    "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/{year}.json",
)
POLISH_PROFILE_LEARNING_ENABLED = (
    os.environ.get("LEGADO_POLISH_PROFILE_LEARNING", "1") == "1"
)
POLISH_PROFILE_SAMPLE_LIMIT = int(
    os.environ.get("LEGADO_POLISH_PROFILE_SAMPLE_LIMIT", "20")
)
POLISH_PROFILE_SAMPLE_CHARS = int(
    os.environ.get("LEGADO_POLISH_PROFILE_SAMPLE_CHARS", "4000")
)
POLISH_PROFILE_SAMPLE_TTL_DAYS = int(
    os.environ.get("LEGADO_POLISH_PROFILE_SAMPLE_TTL_DAYS", "30")
)
"""


def add_app() -> None:
    text = SETTINGS.read_text(encoding="utf-8")
    if "'apps.polish'" in text:
        print("settings.py: apps.polish already present")
        return
    old = "    'apps.legado',\n]"
    new = "    'apps.legado',\n    'apps.polish',\n]"
    if old not in text:
        raise SystemExit(
            "settings.py: 未找到 INSTALLED_APPS 的 apps.legado 行，请手动加入 'apps.polish',"
        )
    SETTINGS.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("settings.py: added apps.polish")


def add_config() -> None:
    text = CONFIG.read_text(encoding="utf-8")
    if "# ************** DeepSeek 正文清洗" in text:
        print("config.py: polish block already present")
        return
    if not text.endswith("\n"):
        text += "\n"
    CONFIG.write_text(text + CONFIG_BLOCK, encoding="utf-8")
    print("config.py: appended polish block")


if __name__ == "__main__":
    add_app()
    add_config()
