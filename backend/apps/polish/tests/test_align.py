from django.test import SimpleTestCase

from apps.polish.align import aggregate_candidates, diff_ops, frequency_flags


class AlignTest(SimpleTestCase):
    def test_deleted_char(self):
        ops = diff_ops("他走进了屋子，坐了下来", "他走进了屋坐了下来")
        self.assertTrue(
            any("子" in op.get("a", "") or "，" in op.get("a", "") for op in ops)
        )

    def test_variant_char(self):
        ops = diff_ops("他站在乾坤之间", "他站在干坤之间")
        self.assertTrue(
            any(op.get("a") == "乾" and op.get("b") == "干" for op in ops)
        )

    def test_antonym(self):
        ops = diff_ops("他很高", "他很低")
        self.assertTrue(
            any(op.get("a") == "高" and op.get("b") == "低" for op in ops)
        )

    def test_aggregate_counts(self):
        pairs = [("乾杯", "干杯"), ("乾坤", "干坤"), ("乾隆", "干隆")]
        result = aggregate_candidates(pairs)
        self.assertEqual(result["char_map"]["乾"]["to"], "干")
        self.assertGreaterEqual(result["char_map"]["乾"]["ratio"], 0.9)

    def test_frequency_flags(self):
        flags = frequency_flags(["他跑得很快。" * 1000])
        self.assertFalse(any(f["char"] == "得" for f in flags))
        flags = frequency_flags(["他跑很快。" * 2000])
        self.assertTrue(any(f["char"] == "得" for f in flags))
