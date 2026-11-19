import json

from django.test import SimpleTestCase, override_settings

from apps.polish.deepseek import build_messages, resolve_model


class DeepSeekMessageTest(SimpleTestCase):
    def test_messages_shape(self):
        messages = build_messages("第1章", "第一段。\n第二段。")
        self.assertEqual(messages[0]["role"], "system")
        self.assertIn("verdict", messages[0]["content"])
        payload = json.loads(messages[1]["content"])
        self.assertEqual(payload["title"], "第1章")
        self.assertEqual(payload["paragraphs"], ["第一段。", "第二段。"])

    @override_settings(POLISH_MODEL="deepseek-flash")
    def test_model_default(self):
        self.assertEqual(resolve_model(None), "deepseek-flash")
