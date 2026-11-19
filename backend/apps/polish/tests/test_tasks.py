from datetime import timedelta

from django.test import TestCase, override_settings
from django.utils import timezone

from apps.legado.models import LegadoUser
from apps.polish.models import PolishJob
from apps.polish.services import release_stale_jobs
from apps.polish.tasks import _decide, process_queue


@override_settings(POLISH_HOLIDAYS=())
class WorkerTest(TestCase):
    def setUp(self):
        self.user = LegadoUser.objects.create(phone="13800000004")

    def test_force_zero_limit(self):
        result = process_queue(limit=0, force=True)
        self.assertEqual(result["processed"], 0)

    def test_release_stale(self):
        job = PolishJob.objects.create(
            user=self.user,
            book_url="b",
            chapter_url="c",
            chapter_index=1,
            source_hash="a" * 32,
            raw_text="正文",
            status="processing",
        )
        PolishJob.objects.filter(id=job.id).update(
            update_time=timezone.now() - timedelta(minutes=20)
        )
        self.assertEqual(release_stale_jobs(), 1)
        job.refresh_from_db()
        self.assertEqual(job.status, "queued")

    def test_decide_contaminated(self):
        self.assertEqual(
            _decide({"verdict": "contaminated", "confidence": 0.92}, [])["verdict"],
            "contaminated",
        )

    def test_decide_paywall_overrides_ok(self):
        self.assertEqual(
            _decide({"verdict": "ok", "confidence": 0.95}, ["paywall"])["verdict"],
            "suspicious",
        )

    def test_decide_low_confidence(self):
        self.assertEqual(
            _decide({"verdict": "ok", "confidence": 0.3}, [])["verdict"],
            "suspicious",
        )
