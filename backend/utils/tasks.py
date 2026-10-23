# -*- coding: utf-8 -*-

"""
@Remark: 定时任务示例函数
"""
import logging

logger = logging.getLogger(__name__)


def example_task():
    """
    示例任务1：打印日志
    调用目标字符串: utils.tasks.example_task
    """
    from django.utils import timezone
    logger.info(f"定时任务执行成功，时间: {timezone.now()}")


def example_task_with_params():
    """
    示例任务2：带参数的任务

    注意：如果任务需要参数，请在函数内部定义，
    调用目标字符串: utils.tasks.example_task_with_params
    """
    import os
    import time

    logger.info("执行带参数的定时任务")
    logger.info(f"当前工作目录: {os.getcwd()}")
    logger.info(f"CPU使用率检查: {time.time()}")


def cleanup_old_logs():
    """
    示例任务3：清理旧日志

    调用目标字符串: utils.tasks.cleanup_old_logs
    """
    from apps.sysjob.models import SysJobLog
    from django.utils import timezone
    from datetime import timedelta

    # 删除30天前的日志
    thirty_days_ago = timezone.now() - timedelta(days=30)
    count = SysJobLog.objects.filter(create_time__lt=thirty_days_ago).delete()[0]
    logger.info(f"已清理 {count} 条30天前的旧日志")


def system_health_check():
    """
    示例任务4：系统健康检查

    调用目标字符串: utils.tasks.system_health_check
    """
    from django.db import connection
    import time

    logger.info("开始执行系统健康检查")

    # 检查数据库连接
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT 1")
            logger.info("数据库连接正常")
    except Exception as e:
        logger.error(f"数据库连接异常: {str(e)}")

    # 检查Redis连接
    try:
        from django_redis import get_redis_connection
        redis_conn = get_redis_connection('default')
        redis_conn.ping()
        logger.info("Redis连接正常")
    except Exception as e:
        logger.error(f"Redis连接异常: {str(e)}")

    logger.info("系统健康检查完成")
