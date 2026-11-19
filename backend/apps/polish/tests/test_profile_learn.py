from django.test import SimpleTestCase

from apps.polish.profile_learn import validate_profile

PROFILE = {"char_map": {"乾": {"to": "干", "exceptions": ["乾坤"]}}}


class ValidateProfileTest(SimpleTestCase):
    def test_false_change_rate(self):
        pairs = [("乾杯", "干杯"), ("乾坤", "乾坤"), ("乾杯", "干杯")]
        rate, gain = validate_profile(PROFILE, pairs)
        self.assertLess(rate, 0.5)
        self.assertGreater(gain, 0)

    def test_no_change(self):
        rate, gain = validate_profile({}, [("原文", "原文")])
        self.assertEqual(rate, 0.0)
        self.assertEqual(gain, 0.0)
