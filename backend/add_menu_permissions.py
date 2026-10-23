#!/usr/bin/env python
# -*- coding: utf-8 -*-
import os
import django

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "application.settings")
django.setup()

import uuid
from django.utils import timezone
from mysystem.models import Menu, MenuButton, Users

print("=== Scheduled Task Menu and Permissions Configuration ===\n")

# 获取系统用户
system_user = Users.objects.filter(username='admin').first()
if not system_user:
    system_user = Users.objects.first()
if not system_user:
    print("ERROR: No users found in database.")
else:
    print(f"System user: {system_user.username} (ID: {system_user.id})\n")

# 用户指定的系统监控模块ID
SYSTEM_MONITOR_ID = '563092a536194a1493551a0043f1f1a3'

# 1. 添加定时任务菜单
print("1. Adding Scheduled Task Menu...")
try:
    system_monitor = Menu.objects.get(id=SYSTEM_MONITOR_ID)
    print(f"   Found parent menu: {system_monitor.name} (ID: {system_monitor.id})")

    defaults = {
        'id': uuid.uuid4(),
        'parent': system_monitor,
        'icon': 'Clock',
        'sort': 105,
        'type': 1,
        'web_path': '/jobManage',
        'visible': True,
        'status': True,
        'isautopm': False,
    }

    if system_user:
        defaults['create_by'] = system_user
        defaults['update_by'] = system_user
        defaults['create_time'] = timezone.now()
        defaults['update_time'] = timezone.now()

    job_menu, created = Menu.objects.get_or_create(
        name='Scheduled Task',
        defaults=defaults
    )

    if created:
        print(f"   [OK] Scheduled Task menu created (ID: {job_menu.id})")
    else:
        print(f"   [INFO] Scheduled Task menu already exists (ID: {job_menu.id})")
except Exception as e:
    print(f"   [ERROR] {str(e)}")

# 2. 添加执行日志菜单
print("\n2. Adding Execution Log Menu...")
try:
    if 'system_monitor' in locals() and system_monitor:
        defaults = {
            'id': uuid.uuid4(),
            'parent': system_monitor,
            'icon': 'Document',
            'sort': 106,
            'type': 1,
            'web_path': '/jobLog',
            'visible': True,
            'status': True,
            'isautopm': False,
        }

        if system_user:
            defaults['create_by'] = system_user
            defaults['update_by'] = system_user
            defaults['create_time'] = timezone.now()
            defaults['update_time'] = timezone.now()

        log_menu, created = Menu.objects.get_or_create(
            name='Execution Log',
            defaults=defaults
        )

        if created:
            print(f"   [OK] Execution Log menu created (ID: {log_menu.id})")
        else:
            print(f"   [INFO] Execution Log menu already exists (ID: {log_menu.id})")
except Exception as e:
    print(f"   [ERROR] {str(e)}")

# 3. 添加定时任务按钮权限
print("\n3. Adding Scheduled Task Button Permissions...")
try:
    if 'job_menu' in locals() and job_menu:
        buttons = [
            {'name': 'Query', 'value': 'scheduler:job:list', 'api': '/api/scheduler/jobs/', 'method': 1},
            {'name': 'Add', 'value': 'scheduler:job:add', 'api': '/api/scheduler/jobs/', 'method': 2},
            {'name': 'Edit', 'value': 'scheduler:job:edit', 'api': '/api/scheduler/jobs/', 'method': 3},
            {'name': 'Delete', 'value': 'scheduler:job:delete', 'api': '/api/scheduler/jobs/', 'method': 4},
            {'name': 'Run', 'value': 'scheduler:job:run', 'api': '/api/scheduler/jobs/{id}/run/', 'method': 2},
            {'name': 'Start', 'value': 'scheduler:job:start', 'api': '/api/scheduler/jobs/{id}/start/', 'method': 2},
            {'name': 'Stop', 'value': 'scheduler:job:stop', 'api': '/api/scheduler/jobs/{id}/stop/', 'method': 2},
        ]

        for btn_data in buttons:
            btn, created = MenuButton.objects.get_or_create(
                menu=job_menu,
                value=btn_data['value'],
                defaults={
                    'name': btn_data['name'],
                    'api': btn_data['api'],
                    'method': btn_data['method'],
                }
            )
            if created:
                print(f"   [OK] Button '{btn.name}' created")
            else:
                print(f"   [INFO] Button '{btn.name}' already exists")
except Exception as e:
    print(f"   [ERROR] {str(e)}")

# 4. 添加执行日志按钮权限
print("\n4. Adding Execution Log Button Permissions...")
try:
    if 'log_menu' in locals() and log_menu:
        log_buttons = [
            {'name': 'Query', 'value': 'scheduler:log:list', 'api': '/api/scheduler/executions/', 'method': 1},
            {'name': 'Delete', 'value': 'scheduler:log:delete', 'api': '/api/scheduler/executions/', 'method': 4},
            {'name': 'Clear', 'value': 'scheduler:log:clear', 'api': '/api/scheduler/executions/clear/', 'method': 4},
        ]

        for btn_data in log_buttons:
            btn, created = MenuButton.objects.get_or_create(
                menu=log_menu,
                value=btn_data['value'],
                defaults={
                    'name': btn_data['name'],
                    'api': btn_data['api'],
                    'method': btn_data['method'],
                }
            )
            if created:
                print(f"   [OK] Button '{btn.name}' created")
            else:
                print(f"   [INFO] Button '{btn.name}' already exists")
except Exception as e:
    print(f"   [ERROR] {str(e)}")

print("\n=== Configuration Complete ===")
print("\nYou can now access:")
print("  - Scheduled Task: /jobManage")
print("  - Execution Log: /jobLog")
