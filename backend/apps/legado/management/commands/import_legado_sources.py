from django.core.management.base import BaseCommand, CommandError
import json

from apps.legado.services.backup_service import import_book_sources


class Command(BaseCommand):
    help = "从 Legado 导出的 bookSources.json 导入书源"

    def add_arguments(self, parser):
        parser.add_argument("path", help="bookSources.json 路径")

    def handle(self, *args, **options):
        try:
            with open(options["path"], "r", encoding="utf-8-sig") as fp:
                data = json.load(fp)
        except Exception as exc:
            raise CommandError(f"读取文件失败: {exc}")
        created, updated = import_book_sources(data)
        self.stdout.write(self.style.SUCCESS(f"导入完成：新增 {created}，更新 {updated}"))
