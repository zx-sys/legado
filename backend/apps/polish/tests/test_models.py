from django.test import TestCase

from apps.legado.models import LegadoUser
from apps.polish.models import (
    PolishBookState,
    PolishJob,
    PolishPatchCache,
    PolishQuota,
)


class PolishModelTest(TestCase):
    def setUp(self):
        self.user = LegadoUser.objects.create(phone="13800000001")

    def test_job_defaults(self):
        job = PolishJob.objects.create(
            user=self.user,
            book_url="https://example.com/book/1",
            chapter_url="https://example.com/book/1/2",
            chapter_index=2,
            source_hash="a" * 32,
        )
        self.assertEqual(job.status, "queued")
        self.assertEqual(job.priority, 100)
        self.assertEqual(job.attempts, 0)

    def test_job_unique_per_hash(self):
        kwargs = dict(
            user=self.user,
            book_url="https://example.com/book/1",
            chapter_url="https://example.com/book/1/2",
            chapter_index=2,
            source_hash="a" * 32,
        )
        PolishJob.objects.create(**kwargs)
        with self.assertRaises(Exception):
            PolishJob.objects.create(**kwargs)

    def test_patch_cache_unique(self):
        PolishPatchCache.objects.create(
            chapter_url="https://example.com/book/1/2",
            source_hash="a" * 32,
            verdict="ok",
            patch_json={"edits": []},
        )
        with self.assertRaises(Exception):
            PolishPatchCache.objects.create(
                chapter_url="https://example.com/book/1/2",
                source_hash="a" * 32,
                verdict="ok",
                patch_json={"edits": []},
            )

    def test_quota_unique_per_day(self):
        PolishQuota.objects.create(user=self.user, date="2026-11-18")
        with self.assertRaises(Exception):
            PolishQuota.objects.create(user=self.user, date="2026-11-18")

    def test_book_state_unique(self):
        PolishBookState.objects.create(
            user=self.user, book_url="https://example.com/book/1"
        )
        with self.assertRaises(Exception):
            PolishBookState.objects.create(
                user=self.user, book_url="https://example.com/book/1"
            )
