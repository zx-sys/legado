# -*- coding: utf-8 -*-
"""
@Remark: 定时任务示例 - 测试任务
"""
import time
import logging
from datetime import datetime
from django.utils import timezone

logger = logging.getLogger(__name__)


def test_task(message="Hello from test task"):
    """
    测试任务函数 - 用于验证定时任务功能
    
    Args:
        message (str): 任务消息参数
        
    Returns:
        str: 任务执行结果
    """
    job_id = f"test_task_{timezone.now().strftime('%Y%m%d%H%M%S')}"
    
    logger.info(f"[{job_id}] Starting test task with message: {message}")
    
    # 模拟任务处理
    time.sleep(2)
    
    # 记录处理过程
    logger.info(f"[{job_id}] Task is processing... current time: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    
    # 再次模拟处理
    time.sleep(1)
    
    result = f"Task completed: {message}"
    logger.info(f"[{job_id}] {result}")
    
    return result


def sample_task(param1="default", param2=None):
    """
    示例任务 - 演示多参数任务
    
    Args:
        param1 (str): 参数 1
        param2 (str, optional): 参数 2
        
    Returns:
        str: 执行结果
    """
    job_id = f"sample_task_{timezone.now().strftime('%Y%m%d%H%M%S')}"
    
    logger.info(f"[{job_id}] Starting sample task with param1={param1}, param2={param2}")
    
    # 模拟业务处理
    time.sleep(1)
    
    result = f"Sample task finished. param1={param1}, param2={param2}"
    logger.info(f"[{job_id}] {result}")
    
    return result


def cleanup_old_logs(days=30):
    """
    清理旧日志任务 - 演示实际业务场景
    
    Args:
        days (int): 保留天数
        
    Returns:
        dict: 清理结果统计
    """
    from apps.sysjob.models import SysJobLog
    
    job_id = f"cleanup_task_{timezone.now().strftime('%Y%m%d%H%M%S')}"
    
    logger.info(f"[{job_id}] Starting cleanup task, will remove logs older than {days} days")
    
    try:
        # 计算截止日期
        from datetime import timedelta
        cutoff_date = timezone.now() - timedelta(days=days)
        
        # 查询旧日志
        old_logs = SysJobLog.objects.filter(create_time__lt=cutoff_date)
        count = old_logs.count()
        
        if count > 0:
            # 删除旧日志
            old_logs.delete()
            result = f"Cleaned up {count} old log records"
        else:
            result = "No old logs to clean up"
            
        logger.info(f"[{job_id}] {result}")
        
        return {
            "status": "success",
            "deleted_count": count,
            "message": result
        }
        
    except Exception as e:
        error_msg = f"Cleanup failed: {str(e)}"
        logger.error(f"[{job_id}] {error_msg}")
        return {
            "status": "error",
            "message": error_msg
        }
