from django.test import SimpleTestCase

from apps.polish.profile_apply import apply_profile

PROFILE = {
    "char_map": {"乾": {"to": "干", "exceptions": ["乾坤", "乾隆"]}},
    "context_rules": [
        {
            "pattern": "(跑|说|唱|跳)(很|非常|太)([\\u4e00-\\u9fa5])",
            "replacement": "$1得$2$3",
            "confidence": 0.9,
        }
    ],
    "antonym_pairs": [
        {"from": "高", "to": "低", "direction": "a2b", "confidence": 0.95}
    ],
}


class ApplyProfileTest(SimpleTestCase):
    def test_char_map_with_exception(self):
        text, changes = apply_profile("乾坤之间，乾杯", PROFILE)
        self.assertIn("乾坤", text)
        self.assertIn("干杯", text)
        self.assertGreaterEqual(len(changes), 1)

    def test_context_rule(self):
        text, _ = apply_profile("他跑很快", PROFILE)
        self.assertIn("跑得很快", text)

    def test_antonym(self):
        text, _ = apply_profile("他很高", PROFILE)
        self.assertEqual(text, "他很低")

    def test_empty_profile(self):
        text, changes = apply_profile("原文", {})
        self.assertEqual(text, "原文")
        self.assertEqual(changes, [])
