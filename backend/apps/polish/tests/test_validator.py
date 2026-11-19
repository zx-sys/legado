from django.test import SimpleTestCase

from apps.polish.validator import validate_payload

RAW = "第一段正常。\n他走进了屋坐了下来。\n最新章节请访问 www.example.com"


class ValidatorTest(SimpleTestCase):
    def test_ok_single_edit(self):
        payload = {
            "verdict": "ok",
            "confidence": 0.9,
            "edits": [
                {"p": 1, "from": "他走进了屋坐了下来", "to": "他走进了屋子，坐了下来"}
            ],
        }
        result = validate_payload(RAW, payload)
        self.assertTrue(result.ok)
        self.assertEqual(result.verdict, "ok")
        self.assertEqual(len(result.edits), 1)
        self.assertEqual(result.edits[0]["replace"], "他走进了屋子，坐了下来")
        self.assertGreater(result.edits[0]["start"], 0)

    def test_contaminated_discards_edits(self):
        payload = {
            "verdict": "contaminated",
            "confidence": 0.92,
            "boundary": 2,
            "reason": "后文是另一本书",
            "edits": [{"p": 0, "from": "第一段正常。", "to": "第一段正常。"}],
        }
        result = validate_payload(RAW, payload)
        self.assertTrue(result.ok)
        self.assertEqual(result.verdict, "contaminated")
        self.assertEqual(result.edits, [])

    def test_anchor_must_be_unique(self):
        payload = {
            "verdict": "ok",
            "confidence": 0.9,
            "edits": [{"p": 0, "from": "第一段", "to": "第一段。"}],
        }
        result = validate_payload("第一段\n第一段", payload)
        self.assertFalse(result.ok)

    def test_anchor_missing(self):
        payload = {
            "verdict": "ok",
            "confidence": 0.9,
            "edits": [{"p": 0, "from": "不存在", "to": "x"}],
        }
        self.assertFalse(validate_payload(RAW, payload).ok)

    def test_reject_new_url(self):
        payload = {
            "verdict": "ok",
            "confidence": 0.9,
            "edits": [{"p": 0, "from": "第一段正常。", "to": "访问 www.evil.com"}],
        }
        self.assertFalse(validate_payload(RAW, payload).ok)

    def test_reject_huge_insert(self):
        payload = {
            "verdict": "ok",
            "confidence": 0.9,
            "edits": [{"p": 0, "from": "第一段正常。", "to": "一" * 100}],
        }
        self.assertFalse(validate_payload(RAW, payload).ok)

    def test_reject_too_much_edit(self):
        raw = "短。\n" * 10
        payload = {
            "verdict": "ok",
            "confidence": 0.9,
            "edits": [{"p": 0, "from": "短。", "to": "很长的一句话。"}],
        }
        self.assertFalse(validate_payload(raw, payload, max_ratio=0.01).ok)

    def test_low_confidence_becomes_suspicious(self):
        payload = {
            "verdict": "ok",
            "confidence": 0.3,
            "edits": [{"p": 1, "from": "他走进了屋坐了下来", "to": "他走进了屋子。"}],
        }
        result = validate_payload(RAW, payload)
        self.assertTrue(result.ok)
        self.assertEqual(result.verdict, "suspicious")
        self.assertEqual(result.edits, [])

    def test_lossless_delete_ad(self):
        payload = {
            "verdict": "ok",
            "confidence": 0.99,
            "edits": [
                {"p": 2, "from": "最新章节请访问 www.example.com", "to": ""}
            ],
        }
        result = validate_payload(RAW, payload)
        self.assertTrue(result.ok)
        self.assertEqual(result.edits[0]["replace"], "")
