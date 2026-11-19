import json

from django.core.management.base import BaseCommand

from apps.polish.holidays import refresh_holidays


class Command(BaseCommand):
    help = "刷新 AI 清洗节假日（默认当前年和下一年）"

    def add_arguments(self, parser):
        parser.add_argument("--year", type=int, action="append")

    def handle(self, *args, **options):
        result = refresh_holidays(years=options["year"] or None)
        self.stdout.write(json.dumps(result, ensure_ascii=False))
