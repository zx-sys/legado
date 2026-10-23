# -*- coding: utf-8 -*-
import json
import logging
import re
from pathlib import Path

from django.http import FileResponse, HttpResponseRedirect
from rest_framework.parsers import FormParser, MultiPartParser
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.legado.models import Backup, LegadoUser, TtsEngine
from apps.legado.services import backup_service

logger = logging.getLogger(__name__)

PHONE_RE = re.compile(r"^\+?\d{6,20}$")


def normalize_phone(raw: str | None) -> str | None:
    if not raw:
        return None
    phone = re.sub(r"[\s\-()]", "", raw.strip())
    return phone if PHONE_RE.fullmatch(phone) else None


def get_user(request):
    phone = normalize_phone(request.headers.get("X-User-Phone"))
    if not phone:
        return None, Response({"detail": "X-User-Phone header is required"}, status=400)
    user, _ = LegadoUser.objects.get_or_create(phone=phone)
    return user, None


def backup_dict(backup: Backup) -> dict:
    return {
        "id": backup.id,
        "device_name": backup.device_name,
        "file_name": backup.file_name,
        "trigger": backup.trigger,
        "status": backup.status,
        "sha256": backup.sha256,
        "size_bytes": backup.size_bytes,
        "item_count": backup.item_count,
        "created_at": backup.create_time.strftime("%Y-%m-%dT%H:%M:%S"),
    }


class NoAuthAPIView(APIView):
    authentication_classes = []
    permission_classes = []


class BackupUploadView(NoAuthAPIView):
    parser_classes = [MultiPartParser, FormParser]

    def post(self, request):
        user, error = get_user(request)
        if error:
            return error
        upload = request.FILES.get("file")
        if not upload:
            return Response({"detail": "file is required"}, status=400)
        if not (upload.name or "").lower().endswith(".zip"):
            return Response({"detail": "only zip backups are supported"}, status=400)
        keep_latest = request.data.get("keep_latest")
        if keep_latest is not None:
            keep_latest = str(keep_latest).lower() not in ("false", "0", "no")
        try:
            backup, created = backup_service.create_backup(
                user,
                upload,
                device_name=request.data.get("device_name"),
                trigger=request.data.get("trigger", "manual"),
                keep_latest=keep_latest,
            )
        except backup_service.BackupError as exc:
            return Response({"detail": str(exc)}, status=413)
        except Exception as exc:
            logger.exception("backup upload failed")
            return Response({"detail": f"backup upload failed: {exc}"}, status=500)
        return Response({"created": created, "backup": backup_dict(backup)})


class BackupListView(NoAuthAPIView):
    def get(self, request):
        user, error = get_user(request)
        if error:
            return error
        device = request.query_params.get("device_name")
        qs = Backup.objects.filter(user=user, status="ready")
        if device:
            qs = qs.filter(device_name=device)
        try:
            limit = min(max(int(request.query_params.get("limit", 50)), 1), 100)
        except ValueError:
            limit = 50
        items = qs.order_by("-create_time")[:limit]
        return Response(
            {"total": qs.count(), "items": [backup_dict(item) for item in items]}
        )


class BackupLatestView(NoAuthAPIView):
    def get(self, request):
        user, error = get_user(request)
        if error:
            return error
        qs = Backup.objects.filter(user=user, status="ready")
        device = request.query_params.get("device_name")
        if device:
            qs = qs.filter(device_name=device)
        backup = qs.order_by("-create_time").first()
        if not backup:
            return Response({"detail": "backup not found"}, status=404)
        return Response(backup_dict(backup))


class BackupDownloadView(NoAuthAPIView):
    def get(self, request, backup_id: int):
        user, error = get_user(request)
        if error:
            return error
        backup = Backup.objects.filter(user=user, id=backup_id).first()
        if not backup:
            return Response({"detail": "backup not found"}, status=404)
        path = backup_service.build_backup_zip(backup)
        return FileResponse(
            open(path, "rb"),
            as_attachment=True,
            filename=backup.file_name or f"backup-{backup.id}.zip",
        )


class BackupDeleteView(NoAuthAPIView):
    def delete(self, request, backup_id: int):
        user, error = get_user(request)
        if error:
            return error
        backup = Backup.objects.filter(user=user, id=backup_id).first()
        if not backup:
            return Response({"detail": "backup not found"}, status=404)
        backup_service.delete_backup(backup)
        return Response({"deleted": True})


class TtsEngineListView(NoAuthAPIView):
    def get(self, request):
        qs = TtsEngine.objects.filter(enabled=True)
        engine_type = request.query_params.get("engine_type")
        if engine_type:
            qs = qs.filter(engine_type=engine_type)
        items = [
            {
                "id": item.id,
                "code": item.code,
                "name": item.name,
                "engine_type": item.engine_type,
                "languages": [v for v in item.languages.split(",") if v],
                "speaker_count": item.speaker_count,
                "version": item.version,
                "license": item.license,
                "description": item.description,
                "icon_url": item.icon_url,
                "package_url": item.package_url,
                "package_size": item.package_size,
                "config": item.config,
                "is_default": item.is_default,
            }
            for item in qs.order_by("sort", "-create_time")
        ]
        return Response({"items": items})


class TtsEngineDownloadView(NoAuthAPIView):
    def get(self, request, code: str):
        engine = TtsEngine.objects.filter(code=code, enabled=True).first()
        if not engine or not engine.package_url:
            return Response({"detail": "engine not found"}, status=404)
        TtsEngine.objects.filter(id=engine.id).update(
            download_count=engine.download_count + 1
        )
        return HttpResponseRedirect(engine.package_url)


class BookSourceListView(NoAuthAPIView):
    def get(self, request):
        from apps.legado.models import BookSource

        rows = [
            source.raw_json
            for source in BookSource.objects.filter(enabled=True).order_by("-weight")
        ]
        return Response({"items": rows})
