# -*- coding: utf-8 -*-
from django.db import migrations

PARENT_ID = "legado-menu-root-0000000000000001"
FONT_MENU_ID = "legado-menu-font-0000000000000001"
TTS_MENU_ID = "legado-menu-tts-0000000000000001"

BUTTONS = [
    ("查询", "fontManage:Search", "/api/legado/font-packs/", 0),
    ("新增", "fontManage:Create", "/api/legado/font-packs/", 1),
    ("详情", "fontManage:Detail", "/api/legado/font-packs/{id}/", 0),
    ("编辑", "fontManage:Update", "/api/legado/font-packs/{id}/", 2),
    ("删除", "fontManage:Delete", "/api/legado/font-packs/{id}/", 3),
    ("上传字体", "fontManage:Upload", "/api/legado/font-packs/{id}/upload_package/", 1),
]


def seed(apps, schema_editor):
    MenuButton = apps.get_model("mysystem", "MenuButton")
    RoleMenuPermission = apps.get_model("mysystem", "RoleMenuPermission")
    RoleMenuButtonPermission = apps.get_model("mysystem", "RoleMenuButtonPermission")
    # 已有 Legado 父菜单的角色，自动获得字体管理菜单
    parent_roles = list(
        RoleMenuPermission.objects.filter(menu_id=PARENT_ID).values("role_id", "data_scope")
    )
    for item in parent_roles:
        RoleMenuPermission.objects.get_or_create(
            role_id=item["role_id"],
            menu_id=FONT_MENU_ID,
            defaults={"data_scope": item["data_scope"]},
        )
    # 按 TTS 引擎的授权角色，给字体管理配同样的按钮权限
    tts_grants = list(
        RoleMenuButtonPermission.objects.filter(
            menu_button__menu_id=TTS_MENU_ID
        ).values("role_id", "data_scope")
    )
    for name, value, api, method in BUTTONS:
        button, _ = MenuButton.objects.update_or_create(
            menu_id=FONT_MENU_ID,
            api=api,
            method=method,
            defaults={"name": name, "value": value},
        )
        for item in tts_grants:
            RoleMenuButtonPermission.objects.get_or_create(
                role_id=item["role_id"],
                menu_button=button,
                defaults={"data_scope": item["data_scope"]},
            )


def unseed(apps, schema_editor):
    MenuButton = apps.get_model("mysystem", "MenuButton")
    RoleMenuButtonPermission = apps.get_model("mysystem", "RoleMenuButtonPermission")
    RoleMenuPermission = apps.get_model("mysystem", "RoleMenuPermission")
    buttons = MenuButton.objects.filter(menu_id=FONT_MENU_ID)
    RoleMenuButtonPermission.objects.filter(menu_button__in=buttons).delete()
    buttons.delete()
    RoleMenuPermission.objects.filter(menu_id=FONT_MENU_ID).delete()


class Migration(migrations.Migration):

    dependencies = [
        ("legado", "0008_seed_font_menu"),
    ]

    operations = [migrations.RunPython(seed, unseed)]
