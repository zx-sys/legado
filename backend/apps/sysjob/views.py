# -*- coding: utf-8 -*-

"""
@Remark: 定时任务管理 - 视图
"""
import importlib
import re
import traceback
from django.utils import timezone
from rest_framework import serializers
from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.events import EVENT_JOB_EXECUTED, EVENT_JOB_ERROR
from django_apscheduler.jobstores import DjangoJobStore

from apps.sysjob.models import SysJob, SysJobLog
from apps.sysjob.serializers import SysJobSerializer, SysJobCreateSerializer, SysJobLogSerializer
from utils.viewset import CustomModelViewSet
from utils.jsonResponse import SuccessResponse, ErrorResponse
from rest_framework.decorators import action
import django_filters
import logging
import time
from datetime import datetime

logger = logging.getLogger(__name__)


# 调度器实例（使用类级变量代替全局变量）
class SchedulerManager:
    """调度器管理器 - 单例模式"""
    _instance = None
    _scheduler = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(SchedulerManager, cls).__new__(cls)
        return cls._instance

    def get_scheduler(self):
        """获取调度器实例"""
        if self._scheduler is None:
            try:
                self._scheduler = BackgroundScheduler()
                self._scheduler.add_jobstore(DjangoJobStore(), 'default')
                self._scheduler.add_listener(job_listener, EVENT_JOB_EXECUTED | EVENT_JOB_ERROR)
                self._scheduler.start()
                logger.info("Django APScheduler启动成功")
            except Exception as e:
                logger.error(f"Django APScheduler启动失败: {str(e)}")
                self._scheduler = None
        return self._scheduler

    def shutdown_scheduler(self):
        """优雅关闭调度器"""
        if self._scheduler is not None:
            try:
                self._scheduler.shutdown(wait=False)
                self._scheduler = None
                logger.info("Django APScheduler已关闭")
            except Exception as e:
                logger.error(f"关闭调度器失败: {str(e)}")


# 全局调度器管理器实例
scheduler_manager = SchedulerManager()


def get_scheduler():
    """
    获取调度器实例（单例模式）
    """
    return scheduler_manager.get_scheduler()


def job_listener(event):
    """
    任务执行监听器
    """
    job_id = event.job_id
    try:
        # 获取业务任务
        sys_job = SysJob.objects.filter(aps_job_id=job_id).first()

        if event.exception:
            # 记录失败日志
            if sys_job:
                SysJobLog.objects.create(
                    aps_execution_id=str(event.job_id),
                    job=sys_job,
                    job_name=sys_job.job_name,
                    job_group=sys_job.job_group,
                    invoke_target=sys_job.invoke_target,
                    job_message=f'任务执行失败: {str(event.exception)}',
                    status='1',
                    exception_info=traceback.format_exc(),
                    start_time=event.scheduled_run_time,
                    end_time=datetime.now(),
                    duration=int((datetime.now() - event.scheduled_run_time).total_seconds() * 1000) if event.scheduled_run_time else None
                )
            else:
                # 如果找不到业务任务，记录基本日志
                SysJobLog.objects.create(
                    aps_execution_id=str(event.job_id),
                    job_name=job_id,
                    job_group='DEFAULT',
                    invoke_target='',
                    job_message=f'任务执行失败: {str(event.exception)}',
                    status='1',
                    exception_info=traceback.format_exc(),
                    start_time=event.scheduled_run_time,
                    end_time=datetime.now(),
                    duration=int((datetime.now() - event.scheduled_run_time).total_seconds() * 1000) if event.scheduled_run_time else None
                )
        else:
            # 记录成功日志
            if sys_job:
                SysJobLog.objects.create(
                    aps_execution_id=str(event.job_id),
                    job=sys_job,
                    job_name=sys_job.job_name,
                    job_group=sys_job.job_group,
                    invoke_target=sys_job.invoke_target,
                    job_message='任务执行成功',
                    status='0',
                    exception_info='',
                    start_time=event.scheduled_run_time,
                    end_time=datetime.now(),
                    duration=int((datetime.now() - event.scheduled_run_time).total_seconds() * 1000) if event.scheduled_run_time else None
                )
            else:
                # 如果找不到业务任务，记录基本日志
                SysJobLog.objects.create(
                    aps_execution_id=str(event.job_id),
                    job_name=job_id,
                    job_group='DEFAULT',
                    invoke_target='',
                    job_message='任务执行成功',
                    status='0',
                    exception_info='',
                    start_time=event.scheduled_run_time,
                    end_time=datetime.now(),
                    duration=int((datetime.now() - event.scheduled_run_time).total_seconds() * 1000) if event.scheduled_run_time else None
                )
    except Exception as e:
        logger.error(f"记录任务日志失败: {str(e)}")


def validate_invoke_target(func_path):
    """
    验证调用目标是否合法（白名单机制）
    只允许导入项目内部模块，防止代码注入
    """
    if not func_path:
        raise ValueError("调用目标不能为空")

    # 验证格式：必须是 module.submodule.function
    if not re.match(r'^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)+$', func_path):
        raise ValueError(f"调用目标格式错误: {func_path}")

    # 分割模块路径和函数名
    parts = func_path.rsplit('.', 1)
    if len(parts) != 2:
        raise ValueError(f"调用目标必须包含模块名和函数名: {func_path}")

    module_name, function_name = parts

    # 安全性检查：只允许导入项目内部模块
    # 获取项目根目录（通常会有 apps 或其他项目特定目录）
    allowed_prefixes = ['apps.', 'utils.', 'config.']

    # 检查模块路径是否以允许的前缀开头
    is_allowed = any(module_name.startswith(prefix) for prefix in allowed_prefixes)

    if not is_allowed:
        logger.warning(f"拒绝导入非项目模块: {module_name}")
        raise PermissionError(f"不允许导入项目外部模块: {module_name}")

    return module_name, function_name


def execute_job_func(func_path):
    """
    执行任务的包装函数 (带安全验证)
    """
    try:
        # 验证并解析调用目标（白名单机制）
        module_name, function_name = validate_invoke_target(func_path)

        # 导入模块
        module = importlib.import_module(module_name)

        # 获取函数
        if not hasattr(module, function_name):
            raise AttributeError(f"模块 {module_name} 中不存在函数: {function_name}")

        func = getattr(module, function_name)

        # 验证是否是可调用对象
        if not callable(func):
            raise TypeError(f"{func_path} 不是一个可调用对象")

        # 执行函数
        result = func()
        return result

    except (ValueError, PermissionError, AttributeError, TypeError) as e:
        logger.error(f"任务执行参数验证失败: {str(e)}")
        raise
    except Exception as e:
        logger.error(f"任务执行错误: {str(e)}")
        raise


def add_job_to_scheduler(sys_job):
    """
    添加任务到调度器
    """
    scheduler = get_scheduler()

    # 解析Cron表达式
    cron_parts = sys_job.cron_expression.split()
    if len(cron_parts) != 6:
        raise serializers.ValidationError("Cron表达式格式错误，应为6位（秒 分 时 日 月 周）")

    second, minute, hour, day, month, day_of_week = cron_parts

    # 创建触发器
    trigger = CronTrigger(
        second=second,
        minute=minute,
        hour=hour,
        day=day,
        month=month,
        day_of_week=day_of_week,
        timezone=timezone.get_current_timezone()
    )

    # 根据 misfire_policy 设置相应的调度器参数
    if sys_job.misfire_policy == '1':  # 立即执行
        coalesce = True
        misfire_grace_time = None
    elif sys_job.misfire_policy == '2':  # 执行一次
        coalesce = True
        misfire_grace_time = 1
    elif sys_job.misfire_policy == '3':  # 放弃执行
        coalesce = False
        misfire_grace_time = None
    else:
        coalesce = False
        misfire_grace_time = None

    max_instances = 1 if sys_job.concurrent == '0' else 5

    # 添加任务到调度器（确保唯一性）
    try:
        # 先移除可能存在的同名任务
        if scheduler.get_job(sys_job.aps_job_id):
            scheduler.remove_job(sys_job.aps_job_id)
            
        scheduler.add_job(
            execute_job_func,
            trigger=trigger,
            args=[sys_job.invoke_target],
            id=sys_job.aps_job_id,
            name=sys_job.job_name,
            jobstore='default',
            coalesce=coalesce,
            misfire_grace_time=misfire_grace_time,
            max_instances=max_instances,
            replace_existing=True
        )
    except Exception as e:
        logger.error(f"添加任务到调度器失败 [job_id: {sys_job.aps_job_id}, policy: {sys_job.misfire_policy}]: {str(e)}")
        logger.error(f"调度器参数 - coalesce: {coalesce}, misfire_grace_time: {misfire_grace_time}, max_instances: {max_instances}")
        raise


def remove_job_from_scheduler(job_id):
    """
    从调度器中移除任务
    """
    scheduler = get_scheduler()
    try:
        if scheduler.get_job(job_id):
            scheduler.remove_job(job_id)
    except Exception as e:
        logger.error(f"从调度器移除任务失败: {str(e)}")


class SysJobTimeFilter(django_filters.rest_framework.FilterSet):
    """
    定时任务 过滤器
    """
    job_name = django_filters.CharFilter(field_name='job_name', lookup_expr='icontains')
    job_group = django_filters.CharFilter(field_name='job_group', lookup_expr='icontains')

    class Meta:
        model = SysJob
        fields = ['job_name', 'job_group', 'status']


class SysJobViewSet(CustomModelViewSet):
    """
    定时任务接口
    list: 查询列表
    create: 新增任务
    retrieve: 任务详情
    update: 修改任务
    destroy: 删除任务
    """
    queryset = SysJob.objects.all().order_by('-create_time')
    serializer_class = SysJobSerializer
    filterset_class = SysJobTimeFilter
    search_fields = ('job_name', 'job_group')

    def get_serializer_class(self):
        """根据操作选择不同的序列化器"""
        if self.action == 'create':
            return SysJobCreateSerializer
        return SysJobSerializer

    def perform_create(self, serializer):
        """
        新增任务
        """
        # 创建业务任务记录
        sys_job = serializer.save()

        # 生成APScheduler的job_id (格式: group.job_uuid)
        job_id = f"{sys_job.job_group}.{sys_job.id}"
        sys_job.aps_job_id = job_id
        sys_job.save()

        # 如果任务状态为正常（'0'），则添加到调度器
        if sys_job.status == '0':
            try:
                add_job_to_scheduler(sys_job)
            except Exception as e:
                logger.error(f"创建任务失败 [job_name: {sys_job.job_name}, job_id: {sys_job.aps_job_id}]: {str(e)}")
                logger.error(f"任务参数 - cron: {sys_job.cron_expression}, policy: {sys_job.misfire_policy}, concurrent: {sys_job.concurrent}")
                # 失败时删除业务记录
                sys_job.delete()
                raise serializers.ValidationError(f"添加任务到调度器失败: {str(e)}")

    def perform_update(self, serializer):
        """
        更新任务
        """
        sys_job = serializer.save()

        # 先从调度器中移除旧任务
        remove_job_from_scheduler(sys_job.aps_job_id)

        # 如果任务状态为正常（'0'），则重新添加到调度器
        if sys_job.status == '0':
            try:
                add_job_to_scheduler(sys_job)
            except Exception as e:
                logger.error(f"更新任务失败 [job_name: {sys_job.job_name}, job_id: {sys_job.aps_job_id}]: {str(e)}")
                logger.error(f"任务参数 - cron: {sys_job.cron_expression}, policy: {sys_job.misfire_policy}, concurrent: {sys_job.concurrent}")
                raise serializers.ValidationError(f"更新任务失败: {str(e)}")

    def perform_destroy(self, instance):
        """
        删除任务
        """
        # 处理 QuerySet 或单个实例的情况
        if hasattr(instance, 'all'):  # 是 QuerySet
            instances = instance
        else:  # 是单个实例
            instances = [instance]
        
        # 从调度器中移除所有任务
        for inst in instances:
            try:
                remove_job_from_scheduler(inst.aps_job_id)
            except Exception as e:
                logger.error(f"从调度器移除任务 {inst.aps_job_id} 失败: {str(e)}")
        
        # 删除业务记录
        if hasattr(instance, 'all'):  # 是 QuerySet
            instance.delete()
        else:  # 是单个实例
            instance.delete()

    @action(methods=['POST'], detail=True)
    def run(self, request, *args, **kwargs):
        """
        立即执行任务
        """
        sys_job = self.get_object()
        
        start_time = datetime.now()
        try:
            # 立即执行任务
            execute_job_func(sys_job.invoke_target)
            end_time = datetime.now()
            duration = int((end_time - start_time).total_seconds() * 1000)
            
            # 记录执行日志
            SysJobLog.objects.create(
                aps_execution_id=f"manual_{sys_job.aps_job_id}_{int(time.time())}",
                job=sys_job,
                job_name=sys_job.job_name,
                job_group=sys_job.job_group,
                invoke_target=sys_job.invoke_target,
                job_message='手动执行任务成功',
                status='0',
                exception_info='',
                start_time=start_time,
                end_time=end_time,
                duration=duration
            )
            
            return SuccessResponse(msg="任务执行成功")
        except Exception as e:
            end_time = datetime.now()
            duration = int((end_time - start_time).total_seconds() * 1000)
            
            # 记录执行日志
            SysJobLog.objects.create(
                aps_execution_id=f"manual_{sys_job.aps_job_id}_{int(time.time())}",
                job=sys_job,
                job_name=sys_job.job_name,
                job_group=sys_job.job_group,
                invoke_target=sys_job.invoke_target,
                job_message=f'手动执行任务失败: {str(e)}',
                status='1',
                exception_info=traceback.format_exc(),
                start_time=start_time,
                end_time=end_time,
                duration=duration
            )
            
            return ErrorResponse(msg=f"任务执行失败: {str(e)}")

    @action(methods=['POST'], detail=True)
    def start(self, request, *args, **kwargs):
        """
        启动任务
        """
        sys_job = self.get_object()
        sys_job.status = '0'
        sys_job.save()

        try:
            add_job_to_scheduler(sys_job)
            return SuccessResponse(msg="任务已启动")
        except Exception as e:
            return ErrorResponse(msg=f"任务启动失败: {str(e)}")

    @action(methods=['POST'], detail=True)
    def stop(self, request, *args, **kwargs):
        """
        停止任务
        """
        sys_job = self.get_object()
        sys_job.status = '1'
        sys_job.save()

        remove_job_from_scheduler(sys_job.aps_job_id)
        return SuccessResponse(msg="任务已停止")


class SysJobLogTimeFilter(django_filters.rest_framework.FilterSet):
    """
    调度日志 过滤器
    """
    begin_at = django_filters.DateTimeFilter(field_name='create_time', lookup_expr='gte')
    end_at = django_filters.DateTimeFilter(field_name='create_time', lookup_expr='lte')
    job_name = django_filters.CharFilter(field_name='job_name', lookup_expr='icontains')
    status = django_filters.CharFilter(field_name='status', lookup_expr='exact')

    class Meta:
        model = SysJobLog
        fields = ['begin_at', 'end_at', 'job_name', 'status']


class SysJobLogViewSet(CustomModelViewSet):
    """
    调度日志接口
    list: 查询列表
    retrieve: 日志详情
    destroy: 删除日志
    """
    queryset = SysJobLog.objects.all().order_by('-create_time')
    serializer_class = SysJobLogSerializer
    filterset_class = SysJobLogTimeFilter
    search_fields = ('job_name', 'job_group', 'job_message')

    def create(self, request, *args, **kwargs):
        """
        日志不支持创建
        """
        return ErrorResponse(msg="日志不支持创建操作")

    def update(self, request, *args, **kwargs):
        """
        日志不支持更新
        """
        return ErrorResponse(msg="日志不支持更新操作")

    @action(methods=['DELETE'], detail=False)
    def clear(self, request, *args, **kwargs):
        """
        清空所有日志
        """
        user = request.user
        if not user.is_authenticated:
            return ErrorResponse(msg="请先登录后再执行此操作", code=401)
        if not user.is_superuser:
            return ErrorResponse(msg="您没有权限执行此操作，需要超级管理员权限", code=403)
        
        # 清空业务日志
        deleted_count, _ = SysJobLog.objects.all().delete()
        logger.info(f"用户 {user.username} 清空了 {deleted_count} 条日志记录")
        return SuccessResponse(msg=f"清空成功，共删除 {deleted_count} 条日志")


# 启动调度器
try:
    get_scheduler()
except Exception as e:
    logger.error(f"启动调度器时出错: {str(e)}")
