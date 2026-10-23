# -*- coding: utf-8 -*-
from django.urls import path
from rest_framework import routers

from apps.legado.views_admin import (
    BackupItemViewSet,
    BackupViewSet,
    BookSourceViewSet,
    LegadoBookViewSet,
    LegadoUserViewSet,
    TtsEngineViewSet,
    UserConfigViewSet,
)
from apps.legado.views_app import (
    BackupDeleteView,
    BackupDownloadView,
    BackupLatestView,
    BackupListView,
    BackupUploadView,
    BookSourceListView,
    TtsEngineDownloadView,
    TtsEngineListView,
)

router = routers.SimpleRouter()
router.register(r"users", LegadoUserViewSet, basename="legado-user")
router.register(r"backups", BackupViewSet, basename="legado-backup")
router.register(r"backup-items", BackupItemViewSet, basename="legado-backup-item")
router.register(r"books", LegadoBookViewSet, basename="legado-book")
router.register(r"user-configs", UserConfigViewSet, basename="legado-user-config")
router.register(r"book-sources", BookSourceViewSet, basename="legado-book-source")
router.register(r"tts-engines", TtsEngineViewSet, basename="legado-tts-engine")

app_urlpatterns = [
    path("app/backups/upload", BackupUploadView.as_view(), name="legado-app-backup-upload"),
    path("app/backups/latest", BackupLatestView.as_view(), name="legado-app-backup-latest"),
    path("app/backups", BackupListView.as_view(), name="legado-app-backup-list"),
    path(
        "app/backups/<int:backup_id>/download",
        BackupDownloadView.as_view(),
        name="legado-app-backup-download",
    ),
    path(
        "app/backups/<int:backup_id>",
        BackupDeleteView.as_view(),
        name="legado-app-backup-delete",
    ),
    path("app/tts/engines", TtsEngineListView.as_view(), name="legado-app-tts-list"),
    path(
        "app/tts/engines/<str:code>/download",
        TtsEngineDownloadView.as_view(),
        name="legado-app-tts-download",
    ),
    path("app/book-sources", BookSourceListView.as_view(), name="legado-app-book-sources"),
]

urlpatterns = app_urlpatterns + router.urls
