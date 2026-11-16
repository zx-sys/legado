# -*- coding: utf-8 -*-
from django.db import migrations

KOKORO_CODE = "kokoro-int8-zh-en"


def remove_kokoro(apps, schema_editor):
    TtsEngine = apps.get_model("legado", "TtsEngine")
    TtsEngine.objects.filter(code=KOKORO_CODE).delete()


def restore_noop(apps, schema_editor):
    # 引擎包仍在 OSS；如需恢复，请在后台重新创建目录项。
    pass


class Migration(migrations.Migration):

    dependencies = [
        ("legado", "0012_seed_custom_source_menu"),
    ]

    operations = [
        migrations.RunPython(remove_kokoro, restore_noop),
    ]
