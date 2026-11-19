from datetime import date

from django.test import TestCase, override_settings

from apps.legado.models import LegadoUser
from apps.polish.models import PolishJob, PolishPatchCache, PolishQuota
from apps.polish.services import budget_left_micros, enqueue_chapters

CHAPTERS = [
    {
        "index": 1,
        "url": "https://e.com/1/1",
        "title": "第1章",
        "hash": "a" * 32,
        "text": "正文",
    },
    {
        "index": 2,
        "url": "https://e.com/1/2",
        "title": "第2章",
        "hash": "b" * 32,
        "text": "正文2",
    },
]


@override_settings(POLISH_DAILY_BUDGET_CNY=2.0)
class QueueServiceTest(TestCase):
    def setUp(self):
        self.user = LegadoUser.objects.create(phone="13800000002")

    def test_enqueue(self):
        result = enqueue_chapters(self.user, "https://e.com/1", "书", "src", CHAPTERS)
        self.assertEqual(result["accepted"], 2)
        self.assertEqual(PolishJob.objects.count(), 2)

    def test_enqueue_dedupes_same_hash(self):
        enqueue_chapters(self.user, "https://e.com/1", "书", "src", CHAPTERS)
        result = enqueue_chapters(self.user, "https://e.com/1", "书", "src", CHAPTERS)
        self.assertEqual(result["accepted"], 0)
        self.assertEqual(result["deduped"], 2)

    def test_enqueue_uses_patch_cache(self):
        PolishPatchCache.objects.create(
            chapter_url="https://e.com/1/1",
            source_hash="a" * 32,
            verdict="contaminated",
            boundary_paragraph=3,
            patch_json={"verdict": "contaminated"},
        )
        result = enqueue_chapters(
            self.user, "https://e.com/1", "书", "src", CHAPTERS[:1]
        )
        self.assertEqual(result["accepted"], 0)
        self.assertEqual(result["deduped"], 1)
        job = PolishJob.objects.get(chapter_index=1)
        self.assertEqual(job.status, "contaminated")
        self.assertEqual(job.cost_micros, 0)

    def test_budget(self):
        PolishQuota.objects.create(
            user=self.user, date=date.today(), spent_micros=1_000_000
        )
        self.assertEqual(budget_left_micros(self.user), 1_000_000)
