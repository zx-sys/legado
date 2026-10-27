# -*- coding: utf-8 -*-
import hashlib
import json
import logging
import time

from django.http import FileResponse, HttpResponse
from django_filters.rest_framework import DjangoFilterBackend
from rest_framework.decorators import action
from rest_framework.filters import SearchFilter, OrderingFilter
from rest_framework.parsers import FormParser, MultiPartParser
from rest_framework.response import Response

from apps.legado.models import (
    Backup,
    BackupItem,
    BackupRecord,
    BookSource,
    FontPack,
    LegadoBook,
    LegadoBookmark,
    LegadoHighlight,
    LegadoUser,
    LegadoUserBookSource,
    TtsEngine,
    UserConfig,
)
from apps.legado.serializers import (
    BackupItemSerializer,
    BackupRecordSerializer,
    BackupSerializer,
    BookSourceSerializer,
    FontPackSerializer,
    LegadoBookSerializer,
    LegadoBookmarkSerializer,
    LegadoHighlightSerializer,
    LegadoUserBookSourceSerializer,
    LegadoUserSerializer,
    TtsEngineSerializer,
    UserConfigSerializer,
)
from apps.legado.services import backup_service
from apps.legado.services.storage import storage
from utils.jsonResponse import DetailResponse, SuccessResponse, ErrorResponse
from utils.viewset import CustomModelViewSet

logger = logging.getLogger(__name__)


class LegadoUserViewSet(CustomModelViewSet):
    queryset = LegadoUser.objects.all()
    serializer_class = LegadoUserSerializer
    search_fields = ("phone", "nickname", "remark")
    filterset_fields = ("is_delete",)
    ordering_fields = ("create_time", "update_time")


class BackupViewSet(CustomModelViewSet):
    queryset = Backup.objects.select_related("user").all()
    serializer_class = BackupSerializer
    search_fields = ("user__phone", "device_name", "file_name")
    filterset_fields = ("status", "trigger", "device_name", "user")
    ordering_fields = ("create_time", "size_bytes", "item_count")
    http_method_names = ["get", "delete", "post", "head", "options"]

    def perform_destroy(self, instance):
        if hasattr(instance, "all"):
            for backup in instance:
                backup_service.delete_backup(backup)
        else:
            backup_service.delete_backup(instance)

    @action(methods=["get"], detail=True)
    def items(self, request, pk=None):
        backup = self.get_object()
        return SuccessResponse(
            data=BackupItemSerializer(backup.items.all(), many=True).data
        )

    @action(methods=["get"], detail=True)
    def records(self, request, pk=None):
        backup = self.get_object()
        dataset = request.query_params.get("dataset")
        qs = backup.records.all()
        if dataset:
            qs = qs.filter(dataset=dataset)
        return SuccessResponse(data=BackupRecordSerializer(qs, many=True).data)

    @action(methods=["post"], detail=True)
    def restore(self, request, pk=None):
        backup = self.get_object()
        result = backup_service.restore_backup_to_modules(backup)
        return SuccessResponse(data=result, msg="已还原到当前模块")

    @action(methods=["get"], detail=True)
    def download(self, request, pk=None):
        backup = self.get_object()
        path = backup_service.build_backup_zip(backup)
        return FileResponse(
            open(path, "rb"),
            as_attachment=True,
            filename=backup.file_name or f"backup-{backup.id}.zip",
        )


class BackupItemViewSet(CustomModelViewSet):
    queryset = BackupItem.objects.all()
    serializer_class = BackupItemSerializer
    filterset_fields = ("backup", "storage")


class LegadoBookViewSet(CustomModelViewSet):
    queryset = LegadoBook.objects.select_related("user").all()
    serializer_class = LegadoBookSerializer
    search_fields = ("name", "author", "user__phone", "origin_name", "group")
    filterset_fields = ("user", "group", "origin_name")
    ordering_fields = ("dur_chapter_time", "update_time", "name")

    @action(methods=["get"], detail=True)
    def bookmarks(self, request, pk=None):
        book = self.get_object()
        return SuccessResponse(
            data=LegadoBookmarkSerializer(book.bookmarks.all(), many=True).data
        )

    @action(methods=["get"], detail=True)
    def highlights(self, request, pk=None):
        book = self.get_object()
        return SuccessResponse(
            data=LegadoHighlightSerializer(book.highlights.all(), many=True).data
        )


class UserConfigViewSet(CustomModelViewSet):
    queryset = UserConfig.objects.select_related("user").all()
    serializer_class = UserConfigSerializer
    search_fields = ("user__phone", "source_file", "config_type")
    filterset_fields = ("user", "config_type")
    ordering_fields = ("update_time", "config_type")

    def update(self, request, *args, **kwargs):
        # 管理端只改内容，PUT 按部分更新处理，避免 user/config_type 必填校验失败
        kwargs["partial"] = True
        return super().update(request, *args, **kwargs)


class LegadoUserBookSourceViewSet(CustomModelViewSet):
    queryset = LegadoUserBookSource.objects.select_related("user").all()
    serializer_class = LegadoUserBookSourceSerializer
    search_fields = ("user__phone", "name", "source_url")
    filterset_fields = ("user", "enabled", "removed")
    ordering_fields = ("source_updated_at", "update_time", "name")

    def perform_destroy(self, instance):
        # 管理端删除写墓碑，让客户端同步时也移除该源，而不是下次又被推回来
        instance.removed = True
        instance.enabled = False
        instance.raw_json = {}
        instance.source_updated_at = int(time.time() * 1000)
        instance.save(
            update_fields=["removed", "enabled", "raw_json", "source_updated_at", "update_time"]
        )


class BookSourceViewSet(CustomModelViewSet):
    queryset = BookSource.objects.all()
    serializer_class = BookSourceSerializer
    search_fields = ("name", "source_url", "group", "comment")
    filterset_fields = ("enabled", "group", "source_type")
    ordering_fields = ("update_time", "weight", "name")

    @action(
        methods=["post"],
        detail=False,
        parser_classes=[MultiPartParser, FormParser],
    )
    def import_json(self, request):
        try:
            upload = request.FILES.get("file")
            if upload:
                data = json.loads(upload.read().decode("utf-8-sig"))
            else:
                data = request.data
            created, updated = backup_service.import_book_sources(data)
        except Exception as exc:
            return ErrorResponse(msg=f"导入失败: {exc}")
        return SuccessResponse(
            data={"created": created, "updated": updated},
            msg=f"导入完成，新增 {created}，更新 {updated}",
        )

    @action(methods=["get"], detail=False)
    def export_json(self, request):
        rows = [item.raw_json for item in self.filter_queryset(self.get_queryset())]
        response = HttpResponse(
            json.dumps(rows, ensure_ascii=False, indent=2),
            content_type="application/json; charset=utf-8",
        )
        response["Content-Disposition"] = 'attachment; filename="book_sources.json"'
        return response


class FontPackViewSet(CustomModelViewSet):
    queryset = FontPack.objects.all()
    serializer_class = FontPackSerializer
    search_fields = ("name", "code", "languages", "description")
    filterset_fields = ("enabled",)
    ordering_fields = ("sort", "create_time", "download_count")

    @action(
        methods=["post"],
        detail=True,
        parser_classes=[MultiPartParser, FormParser],
    )
    def upload_package(self, request, pk=None):
        pack = self.get_object()
        upload = request.FILES.get("file")
        if not upload:
            return ErrorResponse(msg="请选择要上传的字体文件")
        name = (upload.name or "").lower()
        if not name.endswith((".ttf", ".otf", ".ttc")):
            return ErrorResponse(msg="只支持 ttf/otf/ttc 字体文件")
        try:
            data = upload.read()
            key, url = storage.put_font_package(pack.code, upload.name, data)
        except Exception as exc:
            logger.exception("字体包上传失败")
            return ErrorResponse(msg=f"上传失败: {exc}")
        pack.package_key = key or ""
        pack.package_url = url
        pack.package_file_name = upload.name
        pack.package_size = len(data)
        pack.sha256 = hashlib.sha256(data).hexdigest()
        pack.save(
            update_fields=[
                "package_key",
                "package_url",
                "package_file_name",
                "package_size",
                "sha256",
                "update_time",
            ]
        )
        return DetailResponse(data=FontPackSerializer(pack).data, msg="上传成功")

    def perform_destroy(self, instance):
        packs = instance if hasattr(instance, "all") else [instance]
        for pack in packs:
            if pack.package_key:
                storage.delete("oss", pack.package_key, None)
        super().perform_destroy(instance)


class TtsEngineViewSet(CustomModelViewSet):
    queryset = TtsEngine.objects.all()
    serializer_class = TtsEngineSerializer
    search_fields = ("name", "code", "languages", "description")
    filterset_fields = ("enabled", "engine_type", "is_default")
    ordering_fields = ("sort", "create_time", "download_count")

    @action(
        methods=["post"],
        detail=True,
        parser_classes=[MultiPartParser, FormParser],
    )
    def upload_package(self, request, pk=None):
        engine = self.get_object()
        upload = request.FILES.get("file")
        if not upload:
            return ErrorResponse(msg="请选择要上传的引擎包")
        try:
            data = upload.read()
            key, url = storage.put_tts_package(engine.code, upload.name, data)
        except Exception as exc:
            logger.exception("TTS 引擎包上传失败")
            return ErrorResponse(msg=f"上传失败: {exc}")
        engine.package_key = key or ""
        engine.package_url = url
        engine.package_file_name = upload.name
        engine.package_size = len(data)
        engine.save(
            update_fields=[
                "package_key",
                "package_url",
                "package_file_name",
                "package_size",
                "update_time",
            ]
        )
        return DetailResponse(data=TtsEngineSerializer(engine).data, msg="上传成功")

    def perform_destroy(self, instance):
        instances = instance if hasattr(instance, "all") else [instance]
        for engine in instances:
            if engine.package_key:
                storage.delete("oss", engine.package_key, None)
        super().perform_destroy(instance)
