from django.core.management.base import BaseCommand

from apps.polish.profile_learn import build_draft


class Command(BaseCommand):
    help = "从多源对齐样本学习一份源级档案草稿"

    def add_arguments(self, parser):
        parser.add_argument("--source-url", required=True)

    def handle(self, *args, **options):
        obj = build_draft(options["source_url"])
        self.stdout.write(
            f"draft v{obj.version} rate={obj.holdout_false_change_rate}"
        )
