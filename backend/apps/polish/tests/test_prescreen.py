from django.test import SimpleTestCase

from apps.polish.prescreen import guess_boundary, prescreen


class PrescreenTest(SimpleTestCase):
    def test_clean_text(self):
        self.assertEqual(prescreen("他走进屋子，坐了下来。\n窗外下着雨。"), [])

    def test_paywall(self):
        flags = prescreen("正文前几段。\n本章未完，请充值后阅读。")
        self.assertIn("paywall", flags)

    def test_mid_chapter_heading(self):
        text = "第一段。\n第二段。\n第三段。\n第12章 另一个故事\n新的主角出现了。"
        self.assertIn("mid_heading", prescreen(text))

    def test_length_outlier_with_heading(self):
        text = "第一段。\n" + "内容。" * 100 + "\n第12章 另一个故事\n" + "新故事。" * 100
        self.assertIn("length_outlier", prescreen(text, book_median_len=100))

    def test_repeated_block(self):
        block = "这是一段重复出现的文字。" * 20
        text = f"{block}\n中间正常内容。\n{block}"
        self.assertIn("repeated_block", prescreen(text))

    def test_guess_boundary_heading(self):
        self.assertEqual(guess_boundary("a\nb\nc\n第12章 另一个故事\n新故事"), 3)

    def test_guess_boundary_paywall(self):
        self.assertEqual(guess_boundary("a\nb\n本章未完，请充值\nc"), 3)

    def test_guess_boundary_none(self):
        self.assertIsNone(guess_boundary("正常内容。\n第二段。"))
