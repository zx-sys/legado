from django.core.management.base import BaseCommand

from apps.polish.profile_learn import approve


class Command(BaseCommand):
    help = "审核并启用一份源级档案"

    def add_arguments(self, parser):
        parser.add_argument("--source-url", required=True)
        parser.add_argument("--reviewer", required=True)
        parser.add_argument("--note", default="")

    def handle(self, *args, **options):
        obj = approve(options["source_url"], options["reviewer"], options["note"])
        self.stdout.write(f"verified v{obj.version}")
