from django.core.management.base import BaseCommand

from apps.polish.models import PolishSourceProfile


class Command(BaseCommand):
    help = "停用一份源级档案"

    def add_arguments(self, parser):
        parser.add_argument("--source-url", required=True)

    def handle(self, *args, **options):
        n = PolishSourceProfile.objects.filter(
            source_url=options["source_url"]
        ).update(status="disabled")
        self.stdout.write(f"disabled {n}")
