# -*- coding: utf-8 -*-
from django.db import migrations

PARENT_ID = "legado-menu-root-0000000000000001"

MENUS = [
    {
        "id": PARENT_ID,
        "parent_id": None,
        "name": "Legado 管理",
        "icon": "Reading",
        "sort": 20,
        "type": 0,
        "web_path": "/legado",
        "component": "",
        "component_name": "",
        "status": True,
        "visible": True,
        "cache": False,
        "isautopm": False,
    },
    {
        "id": "legado-menu-backup-00000000000001",
        "parent_id": PARENT_ID,
        "name": "备份管理",
        "icon": "Files",
        "sort": 1,
        "type": 1,
        "web_path": "/legado/backupManage",
        "component": "",
        "component_name": "backupManage",
        "status": True,
        "visible": True,
        "cache": False,
        "isautopm": False,
    },
    {
        "id": "legado-menu-books-000000000000001",
        "parent_id": PARENT_ID,
        "name": "书架管理",
        "icon": "Collection",
        "sort": 2,
        "type": 1,
        "web_path": "/legado/bookshelfManage",
        "component": "",
        "component_name": "bookshelfManage",
        "status": True,
        "visible": True,
        "cache": False,
        "isautopm": False,
    },
    {
        "id": "legado-menu-user-config-0000000001",
        "parent_id": PARENT_ID,
        "name": "用户配置",
        "icon": "Setting",
        "sort": 3,
        "type": 1,
        "web_path": "/legado/userConfigManage",
        "component": "",
        "component_name": "userConfigManage",
        "status": True,
        "visible": True,
        "cache": False,
        "isautopm": False,
    },
    {
        "id": "legado-menu-user-000000000000001",
        "parent_id": PARENT_ID,
        "name": "用户管理",
        "icon": "User",
        "sort": 4,
        "type": 1,
        "web_path": "/legado/legadoUserManage",
        "component": "",
        "component_name": "legadoUserManage",
        "status": True,
        "visible": True,
        "cache": False,
        "isautopm": False,
    },
    {
        "id": "legado-menu-source-00000000000001",
        "parent_id": PARENT_ID,
        "name": "书源管理",
        "icon": "Link",
        "sort": 5,
        "type": 1,
        "web_path": "/legado/bookSourceManage",
        "component": "",
        "component_name": "bookSourceManage",
        "status": True,
        "visible": True,
        "cache": False,
        "isautopm": False,
    },
    {
        "id": "legado-menu-tts-0000000000000001",
        "parent_id": PARENT_ID,
        "name": "TTS 引擎",
        "icon": "Headset",
        "sort": 6,
        "type": 1,
        "web_path": "/legado/ttsEngineManage",
        "component": "",
        "component_name": "ttsEngineManage",
        "status": True,
        "visible": True,
        "cache": False,
        "isautopm": False,
    },
]

DEFAULT_TTS = {
    "name": "本地中英双语 TTS",
    "code": "local-neural-tts",
    "engine_type": "sherpa_onnx",
    "languages": "zh,en",
    "speaker_count": 1,
    "version": "v3.0",
    "license": "vits-melo-tts-zh_en",
    "source_url": "https://github.com/k2-fsa/sherpa-onnx",
    "description": "sherpa-onnx + vits-melo-tts-zh_en，离线中英混读，单女声。",
    "package_url": "https://hgcc-test.oss-cn-hangzhou.aliyuncs.com/CourseStandards/legado/tts/vits-melo-tts-zh_en.tar.bz2",
    "package_file_name": "vits-melo-tts-zh_en.tar.bz2",
    "package_size": 167006755,
    "enabled": True,
    "is_default": True,
    "sort": 1,
}


def seed(apps, schema_editor):
    Menu = apps.get_model("mysystem", "Menu")
    for source in MENUS:
        row = dict(source)
        menu_id = row.pop("id")
        Menu.objects.update_or_create(id=menu_id, defaults=row)
    TtsEngine = apps.get_model("legado", "TtsEngine")
    code = DEFAULT_TTS["code"]
    defaults = {k: v for k, v in DEFAULT_TTS.items() if k != "code"}
    TtsEngine.objects.update_or_create(code=code, defaults=defaults)


def unseed(apps, schema_editor):
    Menu = apps.get_model("mysystem", "Menu")
    Menu.objects.filter(id__in=[m["id"] for m in MENUS]).delete()
    TtsEngine = apps.get_model("legado", "TtsEngine")
    TtsEngine.objects.filter(code=DEFAULT_TTS["code"]).delete()


class Migration(migrations.Migration):
    dependencies = [
        ("legado", "0002_userconfig"),
        ("mysystem", "0007_alter_dictionary_options"),
    ]

    operations = [migrations.RunPython(seed, unseed)]
