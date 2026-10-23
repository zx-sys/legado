#!/usr/bin/env python
import os
import django

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "application.settings")
django.setup()

import django
from mysystem.models import Menu, MenuButton

# 查找系统管理模块
system_module = Menu.objects.filter(type=0, name__icontains='system').first()
if system_module:
    print(f"System Module: ID={system_module.id}, Name={system_module.name}")

    # 查找系统管理下的菜单
    children = Menu.objects.filter(parent=system_module.id).order_by('sort').values('id', 'name', 'sort', 'type')
    print("\nChildren:")
    for c in children:
        print(f"  ID={c['id']}, Name={c['name']}, Sort={c['sort']}, Type={c['type']}")
else:
    print("System module not found")
