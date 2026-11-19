import json

from django.core.management.base import BaseCommand

from apps.polish.models import PolishJob


class Command(BaseCommand):
    help = "查看某本书的清洗结果与 diff"

    def add_arguments(self, parser):
        parser.add_argument("--book-url", required=True)
        parser.add_argument("--limit", type=int, default=20)

    def handle(self, *args, **options):
        jobs = (
            PolishJob.objects.filter(book_url=options["book_url"])
            .order_by("chapter_index")[: options["limit"]]
        )
        for job in jobs:
            self.stdout.write(
                json.dumps(
                    {
                        "chapter": job.chapter_index,
                        "title": job.chapter_title,
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
