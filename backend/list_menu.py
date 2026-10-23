#!/usr/bin/env python
import os
import django

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "application.settings")
django.setup()

import django
from mysystem.models import Menu, MenuButton

# 查找所有type=0（模块）的菜单
print("All Module Menus:")
modules = Menu.objects.filter(type=0).values('id', 'name', 'parent')
for m in modules:
    print(f"  ID={m['id']}, Name={m['name']}, Parent={m['parent']}")

print("\n\nAll Menus:")
all_menus = Menu.objects.all().values('id', 'name', 'type', 'parent', 'sort')
for m in all_menus[:20]:
    print(f"  ID={m['id']}, Name={m['name']}, Type={m['type']}, Parent={m['parent']}, Sort={m['sort']}")
