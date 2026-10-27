# -*- coding: utf-8 -*-
from django.db import migrations

PARENT_ID = "legado-menu-root-0000000000000001"
MENU_ID = "legado-menu-user-book-source-0001"

MENU = {
    "id": MENU_ID,
    "parent_id": PARENT_ID,
    "name": "用户自定义书源",
    "icon": "Link",
    "sort": 7,
    "type": 1,
    "web_path": "/legado/userBookSourceManage",
    "component": "",
    "component_name": "userBookSourceManage",
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
    Menu.objects.filter(id=MENU_ID).delete()


class Migration(migrations.Migration):

    dependencies = [
        ("legado", "0011_user_book_source"),
        ("mysystem", "0007_alter_dictionary_options"),
    ]

    operations = [migrations.RunPython(seed, unseed)]
