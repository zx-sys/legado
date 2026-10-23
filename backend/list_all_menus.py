#!/usr/bin/env python
# -*- coding: utf-8 -*-
import os
import django

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "application.settings")
django.setup()

from mysystem.models import Menu

# 查找所有菜单
print("All menus:")
for menu in Menu.objects.all().order_by('type', 'sort'):
    parent_name = menu.parent.name if menu.parent else 'None'
    print(f"  ID={menu.id}, Name={menu.name}, Type={menu.type}, Parent={parent_name}, Sort={menu.sort}")
