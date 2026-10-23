# -*- coding: utf-8 -*-

"""
@Remark: 定时任务管理 - 业务模型
"""
from django.db import models
from django.utils import timezone
from utils.models import table_prefix


class SysJob(models.Model):
    """
    定时任务表 - 业务模型，与django_apscheduler_djangojob关联
    """
    MISFIRE_POLICY_CHOICES = (
        ('1', '立即执行'),
        ('2', '执行一次'),
        ('3', '放弃执行'),
    )

    # 关联django_apscheduler的job_id (django_apscheduler_djangojob的主键)
    aps_job_id = models.CharField(
        max_length=255,
        verbose_name='APScheduler任务ID',
        help_text='关联django-apscheduler的任务ID',
        db_index=True  # 添加索引以提升查询性能
    )

    job_name = models.CharField(max_length=64, verbose_name='任务名称', help_text='任务名称')
    job_group = models.CharField(max_length=64, default='DEFAULT', verbose_name='任务组名', help_text='任务组名')
    invoke_target = models.CharField(max_length=500, verbose_name='调用目标字符串', help_text='调用目标字符串')

    cron_expression = models.CharField(max_length=100, verbose_name='Cron执行表达式', help_text='Cron执行表达式')

    misfire_policy = models.CharField(
        max_length=1,
        choices=MISFIRE_POLICY_CHOICES,
        default='3',
        verbose_name='计划执行错误策略',
        help_text='计划执行错误策略（1立即执行 2执行一次 3放弃执行）'
    )

    concurrent = models.CharField(
        max_length=1,
        default='0',
        verbose_name='是否并发执行',
        help_text='是否并发执行（0否 1是）'
    )

    status = models.CharField(
        max_length=1,
        default='0',
        verbose_name='状态',
        help_text='状态（0正常 1暂停）'
    )

    next_run_time = models.DateTimeField(
        null=True,
        blank=True,
        verbose_name='下次执行时间',
        help_text='下次执行时间'
    )

    create_time = models.DateTimeField(auto_now_add=True, verbose_name='创建时间', help_text='创建时间')
    update_time = models.DateTimeField(auto_now=True, verbose_name='更新时间', help_text='更新时间')

    class Meta:
        db_table = table_prefix + "sys_job"
        verbose_name = '定时任务表'
        verbose_name_plural = verbose_name
        ordering = ('-create_time',)


class SysJobLog(models.Model):
    """
    调度日志表
    """
    # 关联django_apscheduler的execution_id
    aps_execution_id = models.CharField(
        max_length=255,
        verbose_name='APScheduler执行ID',
        help_text='关联django-apscheduler的执行ID'
    )

    job = models.ForeignKey(
        SysJob,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        verbose_name='关联任务',
        help_text='关联任务',
        related_name='job_logs'  # 添加 related_name 以优化反向查询
    )

    job_name = models.CharField(max_length=64, verbose_name='任务名称', help_text='任务名称')
    job_group = models.CharField(max_length=64, verbose_name='任务组名', help_text='任务组名')
    invoke_target = models.CharField(max_length=500, verbose_name='调用目标字符串', help_text='调用目标字符串')

    job_message = models.CharField(
        max_length=500,
        null=True,
        blank=True,
        verbose_name='日志信息',
        help_text='日志信息'
    )

    status = models.CharField(
        max_length=1,
        default='0',
        verbose_name='执行状态',
        help_text='执行状态（0正常 1失败）'
    )

    exception_info = models.TextField(
        default='',
        verbose_name='异常信息',
        help_text='异常信息'
    )
    
    # 新增执行时间相关字段
    duration = models.BigIntegerField(
        null=True,
        blank=True,
        verbose_name='执行耗时(毫秒)',
        help_text='任务执行耗时，单位毫秒'
    )
    
    start_time = models.DateTimeField(
        null=True,
        blank=True,
        verbose_name='开始时间',
        help_text='任务开始执行时间'
    )
    
    end_time = models.DateTimeField(
        null=True,
        blank=True,
        verbose_name='结束时间',
        help_text='任务结束执行时间'
    )

    create_time = models.DateTimeField(
        auto_now_add=True,
        verbose_name='创建时间',
        help_text='创建时间'
    )

    class Meta:
        db_table = table_prefix + "sys_job_log"
        verbose_name = '调度日志表'
        verbose_name_plural = verbose_name
        ordering = ('-create_time',)
