from datetime import datetime

from django.test import SimpleTestCase

from apps.polish.window import TZ, is_idle_window


class IdleWindowTest(SimpleTestCase):
    def test_weekday_peak(self):
        for hour, minute in ((9, 0), (11, 59), (14, 0), (17, 59)):
            self.assertFalse(
                is_idle_window(
                    datetime(2026, 11, 18, hour, minute, tzinfo=TZ), holidays=set()
                )
            )

    def test_weekday_off_peak(self):
        for hour, minute in ((8, 59), (12, 0), (13, 59), (18, 0), (23, 0)):
            self.assertTrue(
                is_idle_window(
                    datetime(2026, 11, 18, hour, minute, tzinfo=TZ), holidays=set()
                )
            )

    def test_weekend_all_idle(self):
        self.assertTrue(
            is_idle_window(datetime(2026, 11, 21, 10, 0, tzinfo=TZ), holidays=set())
        )
        self.assertTrue(
            is_idle_window(datetime(2026, 11, 22, 15, 0, tzinfo=TZ), holidays=set())
        )

    def test_holiday_all_idle(self):
        self.assertTrue(
            is_idle_window(
                datetime(2026, 11, 18, 10, 0, tzinfo=TZ),
                holidays={"2026-11-18"},
            )
        )
