# -*- coding: utf-8 -*-

"""
@Remark: 定时任务管理 - 序列化器
"""
from rest_framework import serializers
from apps.sysjob.models import SysJob, SysJobLog
from utils.serializers import CustomModelSerializer


class SysJobSerializer(CustomModelSerializer):
    """
    定时任务-序列化器
    """
    next_run_time = serializers.DateTimeField(read_only=True, format="%Y-%m-%d %H:%M:%S")
    create_time = serializers.DateTimeField(read_only=True, format="%Y-%m-%d %H:%M:%S")
    update_time = serializers.DateTimeField(read_only=True, format="%Y-%m-%d %H:%M:%S")

    class Meta:
        model = SysJob
        fields = "__all__"
        read_only_fields = ["aps_job_id", "create_time", "update_time"]

    def validate_cron_expression(self, value):
        """
        验证Cron表达式格式（6位：秒 分 时 日 月 周）
        """
        cron_parts = value.split()
        if len(cron_parts) != 6:
            raise serializers.ValidationError("Cron表达式格式错误，应为6位（秒 分 时 日 月 周）")
        return value


class SysJobCreateSerializer(CustomModelSerializer):
    """
    定时任务-创建序列化器
    """
    class Meta:
        model = SysJob
        fields = ['job_name', 'job_group', 'invoke_target', 'cron_expression',
                  'misfire_policy', 'concurrent', 'status']


class SysJobLogSerializer(CustomModelSerializer):
    """
    调度日志-序列化器
    """
    create_time = serializers.DateTimeField(read_only=True, format="%Y-%m-%d %H:%M:%S")

    class Meta:
        model = SysJobLog
        fields = "__all__"
