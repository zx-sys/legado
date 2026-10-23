# -*- coding: utf-8 -*-

"""
@Remark: 定时任务测试脚本 - 用于测试定时任务功能
使用方法：
    1. 创建测试任务
    2. 启动任务
    3. 查看执行日志
"""
import os
import sys
import django

# 添加项目路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'application.settings')
django.setup()

from apps.sysjob.models import SysJob, SysJobLog
from django.utils import timezone


def create_test_job():
    """创建测试任务"""
    print("=" * 50)
    print("创建测试任务")
    print("=" * 50)

    test_job = SysJob.objects.create(
        job_name="测试任务-打印日志",
        job_group="TEST",
        invoke_target="utils.tasks.example_task",
        cron_expression="* * * * * *",  # 每秒执行一次，仅用于测试
        misfire_policy="1",  # 立即执行
        concurrent="0",  # 不允许并发
        status="1"  # 暂停状态
    )

    # 手动设置aps_job_id（模拟API创建流程）
    job_id = f"{test_job.job_group}.{test_job.id}"
    test_job.aps_job_id = job_id
    test_job.save()

    print(f"[OK] 任务创建成功：{test_job.job_name}")
    print(f"   任务ID: {test_job.aps_job_id}")
    print(f"   Cron表达式: {test_job.cron_expression}")
    print(f"   状态: {'正常' if test_job.status == '0' else '暂停'}")
    print()

    return test_job


def list_jobs():
    """列出所有任务"""
    print("=" * 50)
    print("当前任务列表")
    print("=" * 50)

    jobs = SysJob.objects.all()
    if not jobs:
        print("暂无任务")
    else:
        for job in jobs:
            print(f"- {job.job_name} ({job.job_group})")
            print(f"  ID: {job.aps_job_id}")
            print(f"  Cron: {job.cron_expression}")
            print(f"  状态: {'正常' if job.status == '0' else '暂停'}")
            print(f"  下次执行: {job.next_run_time or '未调度'}")
            print()

    return jobs


def list_logs():
    """列出执行日志"""
    print("=" * 50)
    print("执行日志（最近10条）")
    print("=" * 50)

    logs = SysJobLog.objects.all().order_by('-create_time')[:10]
    if not logs:
        print("暂无执行日志")
    else:
        for log in logs:
            status_icon = "[OK]" if log.status == '0' else "[FAIL]"
            print(f"{status_icon} {log.job_name}")
            print(f"   时间: {log.create_time}")
            print(f"   消息: {log.job_message}")
            if log.status == '1' and log.exception_info:
                print(f"   异常: {log.exception_info[:100]}...")
            print()


def test_job_execution():
    """测试功能"""
    print("开始测试定时任务功能")
    print()

    # 创建测试任务
    test_job = create_test_job()

    # 列出所有任务
    list_jobs()

    # 提示用户如何测试
    print("=" * 50)
    print("测试提示")
    print("=" * 50)
    print("1. 可以通过API启动任务:")
    print(f"   POST /api/scheduler/jobs/{test_job.id}/start/")
    print()
    print("2. 可以立即执行任务:")
    print(f"   POST /api/scheduler/jobs/{test_job.id}/run/")
    print()
    print("3. 查看执行日志:")
    print("   GET /api/scheduler/executions/")
    print()
    print("4. 运行此脚本查看日志:")
    print("   python utils/test_scheduler.py")
    print()

    return test_job


if __name__ == '__main__':
    import sys

    if len(sys.argv) > 1:
        command = sys.argv[1]

        if command == 'list':
            list_jobs()
        elif command == 'logs':
            list_logs()
        elif command == 'create':
            create_test_job()
        elif command == 'test':
            test_job_execution()
        else:
            print("未知命令，可用命令: list, logs, create, test")
    else:
        # 默认执行完整测试
        test_job_execution()
        list_logs()
