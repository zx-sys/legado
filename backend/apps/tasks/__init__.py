# -*- coding: utf-8 -*-

"""
@Remark: 定时任务 - 示例任务函数
"""

import logging
from django.utils import timezone

logger = logging.getLogger(__name__)


def hello_world():
    """
    示例任务：打印 Hello World
    用于测试定时任务基本功能
    """
    logger.info(f"[定时任务] Hello World - 执行时间: {timezone.now()}")
    print(f"[定时任务] Hello World - 执行时间: {timezone.now()}")
    return "Hello World"


def clean_temp_files():
    """
    示例任务：清理临时文件
    （模拟定时清理任务）
    """
    logger.info(f"[定时任务] 开始清理临时文件 - {timezone.now()}")
    # 这里可以添加实际的清理逻辑
    print(f"[定时任务] 清理临时文件完成 - {timezone.now()}")
    return "Cleaned 0 temp files"


def send_daily_report():
    """
    示例任务：发送日报
    （模拟定时发送邮件任务）
    """
    logger.info(f"[定时任务] 发送日报 - {timezone.now()}")
    # 这里可以添加实际的邮件发送逻辑
    print(f"[定时任务] 日报发送完成 - {timezone.now()}")
    return "Daily report sent"


def backup_data():
    """
    示例任务：数据备份
    （模拟定时备份任务）
    """
    logger.info(f"[定时任务] 开始数据备份 - {timezone.now()}")
    # 这里可以添加实际的备份逻辑
    print(f"[定时任务] 备份完成 - {timezone.now()}")
    return "Backup completed"


def test_exception():
    """
    示例任务：测试异常处理
    这个任务会抛出异常，用于测试日志记录功能
    """
    logger.info(f"[定时任务] 开始测试异常 - {timezone.now()}")
    try:
        # 模拟一个异常
        raise ValueError("这是一个测试异常")
    except Exception as e:
        logger.error(f"[定时任务] 捕获到异常: {str(e)}")
        raise
