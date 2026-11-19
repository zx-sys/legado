import hashlib
import json
import time
from pathlib import Path

from django.core.management.base import BaseCommand

from apps.legado.models import LegadoUser
from apps.polish.models import PolishJob
from apps.polish.services import enqueue_chapters
from apps.polish.tasks import process_queue

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures"


class Command(BaseCommand):
    help = "用真实 DeepSeek 跑 3 个合成章节（需要 LEGADO_DEEPSEEK_API_KEY）"

    def handle(self, *args, **options):
        user, _ = LegadoUser.objects.get_or_create(phone="19900000000")
        suffix = str(int(time.time()))
        book_url = f"https://smoke.local/book/{suffix}"
        chapters = []
        for index, name in enumerate(
            ["clean_ok.txt", "ad_and_missing.txt", "spliced.txt"]
        ):
            text = (FIXTURES / name).read_text(encoding="utf-8").strip()
            chapters.append(
                {
                    "index": index,
                    "url": f"https://smoke.local/{suffix}/{index}",
                    "title": f"第{index + 1}章",
                    "hash": hashlib.md5(text.encode("utf-8")).hexdigest(),
                    "text": text,
                }
            )
        enqueue_chapters(
            user, book_url, "冒烟书", "smoke", chapters,
            priority=0, book_author="冒烟作者",
        )
        result = process_queue(limit=3, force=True)
        self.stdout.write(json.dumps(result, ensure_ascii=False))
        for job in PolishJob.objects.filter(book_url=book_url).order_by("chapter_index"):
            self.stdout.write(
                json.dumps(
                    {
                        "chapter": job.chapter_index,
                        "status": job.status,
                        "verdict": job.verdict,
                        "boundary": job.boundary_paragraph,
                        "reason": job.verdict_reason,
                        "costMicros": job.cost_micros,
                        "edits": (job.patch_json or {}).get("edits", []),
                    },
                    ensure_ascii=False,
                )
            )
