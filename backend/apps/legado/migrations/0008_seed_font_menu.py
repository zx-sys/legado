# -*- coding: utf-8 -*-
from django.db import migrations

PARENT_ID = "legado-menu-root-0000000000000001"

MENU = {
    "id": "legado-menu-font-0000000000000001",
    "parent_id": PARENT_ID,
    "name": "字体管理",
    "icon": "Brush",
    "sort": 7,
    "type": 1,
    "web_path": "/legado/fontManage",
    "component": "",
    "component_name": "fontManage",
    "status": True,
    "visible": True,
    "cache": False,
    "isautopm": False,
}


def seed(apps, schema_editor):
    Menu = apps.get_model("mysystem", "Menu")
    row = dict(MENU)
    menu_id = row.pop("id")
    Menu.objects.update_or_create(id=menu_id, defaults=row)


def unseed(apps, schema_editor):
    Menu = apps.get_model("mysystem", "Menu")
    Menu.objects.filter(id=MENU["id"]).delete()


class Migration(migrations.Migration):

    dependencies = [
        ("legado", "0007_fontpack"),
        ("mysystem", "0007_alter_dictionary_options"),
    ]

    operations = [migrations.RunPython(seed, unseed)]
