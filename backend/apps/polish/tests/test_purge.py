from datetime import timedelta

from django.test import TestCase
from django.utils import timezone

from apps.legado.models import LegadoUser
from apps.polish.models import PolishJob
from apps.polish.tasks import purge_raw_texts


class PurgeTest(TestCase):
    def test_purge_failed_after_days(self):
        user = LegadoUser.objects.create(phone="13800000005")
        job = PolishJob.objects.create(
            user=user,
            book_url="b",
            chapter_url="c",
            chapter_index=1,
            source_hash="a" * 32,
            raw_text="正文",
            status="failed",
        )
        PolishJob.objects.filter(id=job.id).update(
            update_time=timezone.now() - timedelta(days=8)
        )
        self.assertEqual(purge_raw_texts(days=7), 1)
        job.refresh_from_db()
        self.assertIsNone(job.raw_text)
