from django.test import TestCase

from apps.polish.align import aggregate_candidates
from apps.polish.profile_apply import apply_profile


class ProfileE2ETest(TestCase):
    def test_learn_dede_and_qian(self):
        good = [
            "他跑得很快。",
            "她说得很好。",
            "他站在干净的屋子。",
            "他喝了一杯干啤。",
        ]
        bad = [
            "他跑很快。",
            "她说很好。",
            "他站在乾净的屋子。",
            "他喝了一杯干啤。",
        ]
        candidates = aggregate_candidates(list(zip(bad, good)))
        self.assertIn("得", candidates["missing_chars"])
        self.assertEqual(candidates["char_map"]["乾"]["to"], "干")

        profile = {
            "char_map": {"乾": {"to": "干", "exceptions": ["乾坤"]}},
            "context_rules": [
                {
                    "pattern": "([跑说唱跳])(很|非常|太)([\\u4e00-\\u9fa5])",
                    "replacement": "$1得$2$3",
                    "confidence": 0.9,
                }
            ],
            "antonym_pairs": [],
        }
        out, _ = apply_profile("他跑很快。她说很好。他站在乾净的屋子。", profile)
        self.assertEqual(out, "他跑得很快。她说得很好。他站在干净的屋子。")
