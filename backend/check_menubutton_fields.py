#!/usr/bin/env python
# -*- coding: utf-8 -*-
import os
import django

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "application.settings")
django.setup()

from mysystem.models import MenuButton

print("MenuButton fields:")
for f in MenuButton._meta.fields:
    print(f"  {f.name} ({f.__class__.__name__})")
