from django.core.management.base import BaseCommand

from apps.polish.profile_samples import purge_expired_samples


class Command(BaseCommand):
    help = "清理过期的源特征对齐样本"

    def handle(self, *args, **options):
        n = purge_expired_samples()
        self.stdout.write(f"purged {n}")
