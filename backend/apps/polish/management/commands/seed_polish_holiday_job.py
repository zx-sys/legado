from django.core.management.base import BaseCommand

from apps.sysjob.models import SysJob
from apps.sysjob.views import add_job_to_scheduler


class Command(BaseCommand):
    help = "创建/更新每月 1 号刷新节假日的任务"

    def handle(self, *args, **options):
        job, _ = SysJob.objects.update_or_create(
            aps_job_id="polish_holidays",
            defaults={
                "job_name": "AI 清洗节假日刷新",
                "job_group": "DEFAULT",
                "invoke_target": "apps.polish.holidays.refresh_holidays",
                "cron_expression": "0 30 3 1 * *",
                "misfire_policy": "2",
                "concurrent": "0",
                "status": "0",
            },
        )
        add_job_to_scheduler(job)
        self.stdout.write(self.style.SUCCESS("polish_holidays 已注册"))
