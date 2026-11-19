from datetime import datetime

from django.core.cache import cache
from django.test import SimpleTestCase, TestCase, override_settings

from apps.polish.holidays import (
    HOLIDAY_CACHE_KEY,
    HolidayDataError,
    get_polish_holidays,
    parse_holiday_payload,
    refresh_holidays,
)
from apps.polish.window import TZ, is_idle_window
from mysystem.models import SystemConfig

SAMPLE = {
    "year": 2026,
    "days": [
        {"name": "元旦", "date": "2026-01-01", "isOffDay": True},
        {"name": "元旦", "date": "2026-01-02", "isOffDay": True},
        {"name": "元旦", "date": "2026-01-03", "isOffDay": True},
        {"name": "春节", "date": "2026-02-15", "isOffDay": True},
        {"name": "春节", "date": "2026-02-16", "isOffDay": True},
        {"name": "春节", "date": "2026-02-17", "isOffDay": True},
        {"name": "调休上班", "date": "2026-01-04", "isOffDay": False},
    ],
}


class ParseHolidayTest(SimpleTestCase):
    def test_parse_off_days_only(self):
        dates = parse_holiday_payload(SAMPLE, 2026)
        self.assertEqual(len(dates), 6)
        self.assertIn("2026-01-01", dates)
        self.assertNotIn("2026-01-04", dates)

    def test_reject_abnormal_count(self):
        with self.assertRaises(HolidayDataError):
            parse_holiday_payload({"days": SAMPLE["days"][:2]}, 2026)


class RefreshHolidayTest(TestCase):
    def setUp(self):
        cache.delete(HOLIDAY_CACHE_KEY)

    def test_refresh_writes_config_and_cache(self):
        result = refresh_holidays(years=[2026], fetcher=lambda year: SAMPLE)
        self.assertEqual(result["years"], [2026])
        self.assertEqual(result["count"], 6)
        row = SystemConfig.objects.get(key="polish_holidays")
        self.assertIn("2026-01-01", row.value)
        self.assertEqual(len(get_polish_holidays()), 6)
        self.assertTrue(
            is_idle_window(datetime(2026, 1, 1, 10, 0, tzinfo=TZ))
        )

    def test_failure_keeps_existing(self):
        refresh_holidays(years=[2026], fetcher=lambda year: SAMPLE)
        cache.delete(HOLIDAY_CACHE_KEY)

        def boom(year):
            raise RuntimeError("network down")

        with self.assertRaises(RuntimeError):
            refresh_holidays(years=[2026], fetcher=boom)
        self.assertEqual(len(get_polish_holidays()), 6)


class HolidayFallbackTest(TestCase):
    def setUp(self):
        cache.delete(HOLIDAY_CACHE_KEY)

    @override_settings(POLISH_HOLIDAYS=("2026-01-01",))
    def test_settings_fallback(self):
        self.assertEqual(get_polish_holidays(), {"2026-01-01"})

    @override_settings(POLISH_HOLIDAYS=("2026-01-01", ...))
    def test_invalid_settings_entries_ignored(self):
        self.assertEqual(get_polish_holidays(), {"2026-01-01"})

    def test_invalid_cache_entries_ignored(self):
        cache.set(HOLIDAY_CACHE_KEY, {"2026-01-01", ...}, 300)
        self.assertEqual(get_polish_holidays(), {"2026-01-01"})
