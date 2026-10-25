import sys

from django.apps import AppConfig

SKIP_COMMANDS = {"migrate", "makemigrations", "collectstatic", "showmigrations", "check"}


class LegadoConfig(AppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name = "apps.legado"
    verbose_name = "Legado 管理"

    def ready(self):
        if len(sys.argv) > 1 and sys.argv[1] in SKIP_COMMANDS:
            return
        from apps.legado.services.sync_service import start_flusher

        start_flusher()
