from django.core.management.base import BaseCommand

from apps.sysjob.models import SysJob
from apps.sysjob.views import add_job_to_scheduler


class Command(BaseCommand):
    help = "创建/更新每 5 分钟跑一次的 AI 清洗任务"

    def handle(self, *args, **options):
        job, _ = SysJob.objects.update_or_create(
            aps_job_id="polish_queue",
            defaults={
                "job_name": "AI 正文清洗队列",
                "job_group": "DEFAULT",
                "invoke_target": "apps.polish.tasks.process_queue",
                "cron_expression": "0 */5 * * * *",
                "misfire_policy": "2",
                "concurrent": "0",
                "status": "0",
            },
        )
        add_job_to_scheduler(job)
        self.stdout.write(self.style.SUCCESS("polish_queue 已注册"))
