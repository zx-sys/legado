import json

from django.core.management.base import BaseCommand

from apps.polish.tasks import process_queue


class Command(BaseCommand):
    help = "手动跑一次 AI 清洗队列"

    def add_arguments(self, parser):
        parser.add_argument("--once", action="store_true")
        parser.add_argument("--force", action="store_true")
        parser.add_argument("--limit", type=int, default=20)

    def handle(self, *args, **options):
        result = process_queue(limit=options["limit"], force=options["force"])
        self.stdout.write(json.dumps(result, ensure_ascii=False))
