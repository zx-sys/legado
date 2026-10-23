# -*- coding: utf-8 -*-

"""
@Remark: 定时任务管理应用
"""
from django.apps import AppConfig


class SysjobConfig(AppConfig):
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'apps.sysjob'
    verbose_name = '定时任务管理'

    def ready(self):
        """
        应用启动时启动调度器
        """
        # 延迟导入，避免循环依赖
        import apps.sysjob.views  # noqa: F401
