from datetime import timedelta

from django.test import TestCase, override_settings
from django.utils import timezone

from apps.polish.models import PolishAlignSample
from apps.polish.profile_samples import maybe_store_sample, purge_expired_samples


@override_settings(
    POLISH_PROFILE_SAMPLE_LIMIT=4,
    POLISH_PROFILE_SAMPLE_CHARS=10,
    POLISH_PROFILE_SAMPLE_TTL_DAYS=30,
)
class SampleTest(TestCase):
    def test_store_trims_and_limits(self):
        self.assertTrue(maybe_store_sample("s", "b|a", "b", "c1", 1, "第1章", "一" * 100))
        self.assertTrue(maybe_store_sample("s", "b|a", "b", "c2", 2, "第2章", "二" * 100))
        self.assertFalse(maybe_store_sample("s", "b|a", "b", "c3", 3, "第3章", "三" * 100))
        self.assertEqual(PolishAlignSample.objects.count(), 2)
        self.assertEqual(len(PolishAlignSample.objects.first().text), 10)

    @override_settings(POLISH_PROFILE_SAMPLE_LIMIT=2)
    def test_paired_sample_uses_reserved_quota(self):
        PolishAlignSample.objects.create(
            source_url="other", book_key="b|a", book_url="b2", chapter_url="c9",
            chapter_key="k9", chapter_index=9, text="好文本", content_hash="b" * 32,
        )
        self.assertTrue(maybe_store_sample("s", "b|a", "b", "c1", 1, "第1章", "一" * 100))
        self.assertTrue(maybe_store_sample("s", "b|a", "b", "c9", 9, "第9章", "九" * 100))

    def test_dedupe_chapter(self):
        maybe_store_sample("s", "b|a", "b", "c1", 1, "第1章", "一" * 100)
        self.assertFalse(maybe_store_sample("s", "b|a", "b", "c1", 1, "第1章", "一" * 100))

    def test_purge_expired(self):
        sample = PolishAlignSample.objects.create(
            source_url="s", book_key="b|a", book_url="b", chapter_url="c", text="x",
            chapter_key="k", content_hash="a" * 32, chapter_index=1,
            expire_time=timezone.now() - timedelta(days=1),
        )
        self.assertEqual(purge_expired_samples(), 1)
        self.assertFalse(PolishAlignSample.objects.filter(id=sample.id).exists())
