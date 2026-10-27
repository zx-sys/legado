# -*- coding: utf-8 -*-
from django.urls import path
from rest_framework import routers

from apps.legado.views_admin import (
    BackupItemViewSet,
    FontPackViewSet,
    BackupViewSet,
    BookSourceViewSet,
    LegadoBookViewSet,
    LegadoUserBookSourceViewSet,
    LegadoUserViewSet,
    TtsEngineViewSet,
    UserConfigViewSet,
)
from apps.legado.views_app import (
    AccountView,
    BackupDeleteView,
    BackupDownloadView,
    BackupLatestView,
    BackupListView,
    BackupUploadView,
    BookSourceConfigView,
    BookSourceListView,
    CustomBookSourceView,
    FontPackDownloadView,
    FontPackListView,
    SyncView,
    TtsEngineDownloadView,
    TtsEngineListView,
)

router = routers.SimpleRouter()
router.register(r"users", LegadoUserViewSet, basename="legado-user")
router.register(
    r"user-book-sources",
    LegadoUserBookSourceViewSet,
    basename="legado-user-book-source",
)
router.register(r"backups", BackupViewSet, basename="legado-backup")
router.register(r"backup-items", BackupItemViewSet, basename="legado-backup-item")
router.register(r"books", LegadoBookViewSet, basename="legado-book")
router.register(r"user-configs", UserConfigViewSet, basename="legado-user-config")
router.register(r"book-sources", BookSourceViewSet, basename="legado-book-source")
router.register(r"tts-engines", TtsEngineViewSet, basename="legado-tts-engine")
router.register(r"font-packs", FontPackViewSet, basename="legado-font-pack")

app_urlpatterns = [
    path("app/account", AccountView.as_view(), name="legado-app-account"),
    path("app/sync", SyncView.as_view(), name="legado-app-sync"),
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
    path(
        "app/custom-book-sources",
        CustomBookSourceView.as_view(),
        name="legado-app-custom-book-sources",
    ),
    path("app/fonts", FontPackListView.as_view(), name="legado-app-font-list"),
    path(
        "app/fonts/<str:code>/download",
        FontPackDownloadView.as_view(),
        name="legado-app-font-download",
    ),
    path(
        "app/book-source-config",
        BookSourceConfigView.as_view(),
        name="legado-app-book-source-config",
    ),
]

urlpatterns = app_urlpatterns + router.urls
