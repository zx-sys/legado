from django.test import TestCase

from apps.polish.models import PolishAlignSample, PolishSourceProfile


class ProfileModelTest(TestCase):
    def test_profile_unique_source(self):
        PolishSourceProfile.objects.create(source_url="https://src", profile_json={})
        with self.assertRaises(Exception):
            PolishSourceProfile.objects.create(source_url="https://src", profile_json={})

    def test_sample_unique_chapter(self):
        PolishAlignSample.objects.create(
            source_url="https://src", book_key="b|a", book_url="b", chapter_url="c",
            chapter_key="k", text="正文", content_hash="a" * 32,
        )
        with self.assertRaises(Exception):
            PolishAlignSample.objects.create(
                source_url="https://src", book_key="b|a", book_url="b", chapter_url="c",
                chapter_key="k", text="正文", content_hash="a" * 32,
            )
