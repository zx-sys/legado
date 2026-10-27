# -*- coding: utf-8 -*-
import json
import logging
import re
import time
from pathlib import Path
from zoneinfo import ZoneInfo

from django.conf import settings
from django.db import transaction
from django.http import FileResponse, HttpResponseRedirect
from rest_framework.parsers import FormParser, MultiPartParser
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.legado.models import (
    Backup,
    FontPack,
    LegadoUser,
    LegadoUserBookSource,
    TtsEngine,
    UserConfig,
)
from apps.legado.services import backup_service, realtime, sync_service

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
    # USE_TZ=False 下 create_time 是本地时间, 必须带时区返回, 否则客户端会按 UTC 解析
    created_at = backup.create_time
    if created_at is not None:
        tz = ZoneInfo(settings.TIME_ZONE)
        created_at_text = created_at.replace(tzinfo=tz).isoformat()
    else:
        created_at_text = ""
    return {
        "id": backup.id,
        "device_name": backup.device_name,
        "file_name": backup.file_name,
        "trigger": backup.trigger,
        "status": backup.status,
        "sha256": backup.sha256,
        "size_bytes": backup.size_bytes,
        "item_count": backup.item_count,
        "created_at": created_at_text,
    }


class NoAuthAPIView(APIView):
    authentication_classes = []
    permission_classes = []


def account_payload(user: LegadoUser | None) -> dict:
    if user is None:
        return {"exists": False, "backup_count": 0, "latest_backup": None}
    backups = Backup.objects.filter(user=user, status="ready")
    latest = backups.order_by("-create_time").first()
    return {
        "exists": True,
        "backup_count": backups.count(),
        "latest_backup": backup_dict(latest) if latest else None,
    }


class AccountView(NoAuthAPIView):
    """手机号即账号：查询是否已有用户/备份，或创建新用户。"""

    def get(self, request):
        phone = normalize_phone(
            request.query_params.get("phone") or request.headers.get("X-User-Phone")
        )
        if not phone:
            return Response({"detail": "valid phone is required"}, status=400)
        return Response(account_payload(LegadoUser.objects.filter(phone=phone).first()))

    def post(self, request):
        phone = normalize_phone(
            request.data.get("phone") or request.headers.get("X-User-Phone")
        )
        if not phone:
            return Response({"detail": "valid phone is required"}, status=400)
        user, created = LegadoUser.objects.get_or_create(phone=phone)
        payload = account_payload(user)
        payload["created"] = created
        return Response(payload)


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


def _string_list(value) -> list[str]:
    if not isinstance(value, list):
        return []
    result = []
    seen = set()
    for item in value[:5000]:
        if not isinstance(item, str):
            continue
        text = item.strip()
        if text and text not in seen:
            seen.add(text)
            result.append(text)
    return result


class BookSourceConfigView(NoAuthAPIView):
    """用户的书源选择：禁用的书源 URL 与主动排除的 URL。"""

    def get(self, request):
        user, error = get_user(request)
        if error:
            return error
        config = UserConfig.objects.filter(
            user=user, config_type="book_source_config"
        ).first()
        data = config.raw_json if config and isinstance(config.raw_json, dict) else {}
        return Response(
            {
                "disabled": _string_list(data.get("disabled")),
                "excluded": _string_list(data.get("excluded")),
                "updated_at": int(data.get("updated_at") or 0),
            }
        )

    def put(self, request):
        user, error = get_user(request)
        if error:
            return error
        disabled = _string_list(request.data.get("disabled"))
        excluded = _string_list(request.data.get("excluded"))
        try:
            updated_at = int(request.data.get("updated_at") or 0)
        except (TypeError, ValueError):
            updated_at = 0
        if updated_at <= 0:
            updated_at = int(time.time() * 1000)
        payload = {
            "disabled": disabled,
            "excluded": excluded,
            "updated_at": updated_at,
        }
        UserConfig.objects.update_or_create(
            user=user,
            config_type="book_source_config",
            defaults={
                "source_file": "bookSourceConfig.json",
                "content": json.dumps(payload, ensure_ascii=False),
                "raw_json": payload,
            },
        )
        return Response(payload)

CUSTOM_SOURCE_MAX_ITEMS = 2000
CUSTOM_SOURCE_MAX_BYTES = 512 * 1024


def _now_ms() -> int:
    return int(time.time() * 1000)


def _string(value, limit: int = 700) -> str:
    return str(value or "").strip()[:limit]


def _normalize_custom_source(row) -> dict | None:
    if not isinstance(row, dict):
        return None
    source_url = _string(row.get("sourceUrl"))
    if not source_url:
        return None
    removed = bool(row.get("removed", False))
    raw = row.get("source")
    if removed:
        raw = {}
    elif not isinstance(raw, dict):
        return None
    else:
        raw = dict(raw)
        raw["bookSourceUrl"] = source_url
        encoded = json.dumps(raw, ensure_ascii=False)
        if len(encoded.encode("utf-8")) > CUSTOM_SOURCE_MAX_BYTES:
            return None
        raw = json.loads(encoded)
    try:
        updated_at = int(row.get("updatedAt") or 0)
    except (TypeError, ValueError):
        updated_at = 0
    if updated_at <= 0:
        updated_at = _now_ms()
    # 客户端时钟可能超前；避免单台设备长期压住其它设备
    updated_at = min(updated_at, _now_ms() + 60_000)
    return {
        "source_url": source_url,
        "name": _string(row.get("name") or (raw.get("bookSourceName") if raw else ""), 255),
        "enabled": bool(row.get("enabled", True)),
        "removed": removed,
        "updated_at": updated_at,
        "raw_json": raw,
    }


def _custom_source_payload(item: LegadoUserBookSource) -> dict:
    return {
        "sourceUrl": item.source_url,
        "name": item.name,
        "enabled": item.enabled,
        "removed": item.removed,
        "updatedAt": item.source_updated_at,
        "source": item.raw_json if not item.removed else None,
    }


class CustomBookSourceView(NoAuthAPIView):
    """用户自定义书源：按手机号账号隔离，客户端 LWW 合并，删除写墓碑。"""

    def get(self, request):
        user, error = get_user(request)
        if error:
            return error
        try:
            since = int(request.query_params.get("since") or 0)
        except (TypeError, ValueError):
            since = 0
        query = LegadoUserBookSource.objects.filter(user=user, is_delete=False)
        if since > 0:
            query = query.filter(source_updated_at__gt=since)
        items = [_custom_source_payload(item) for item in query.order_by("source_updated_at")]
        return Response({"items": items, "serverTime": _now_ms()})

    def post(self, request):
        user, error = get_user(request)
        if error:
            return error
        rows = request.data.get("items")
        if not isinstance(rows, list):
            return Response({"detail": "items must be a list"}, status=400)
        normalized = [
            item for item in (
                _normalize_custom_source(row) for row in rows[:CUSTOM_SOURCE_MAX_ITEMS]
            ) if item is not None
        ]
        stored = []
        with transaction.atomic():
            for item in normalized:
                obj = (
                    LegadoUserBookSource.objects.select_for_update()
                    .filter(user=user, source_url=item["source_url"])
                    .first()
                )
                if obj is None:
                    obj = LegadoUserBookSource.objects.create(
                        user=user,
                        source_url=item["source_url"],
                        name=item["name"],
                        enabled=item["enabled"],
                        removed=item["removed"],
                        source_updated_at=item["updated_at"],
                        raw_json=item["raw_json"],
                    )
                elif item["updated_at"] >= (obj.source_updated_at or 0):
                    obj.name = item["name"]
                    obj.enabled = item["enabled"]
                    obj.removed = item["removed"]
                    obj.source_updated_at = item["updated_at"]
                    obj.raw_json = item["raw_json"]
                    obj.is_delete = False
                    obj.save(
                        update_fields=[
                            "name",
                            "enabled",
                            "removed",
                            "source_updated_at",
                            "raw_json",
                            "is_delete",
                            "update_time",
                        ]
                    )
                stored.append(obj)
        return Response(
            {"items": [_custom_source_payload(item) for item in stored], "serverTime": _now_ms()}
        )


class FontPackListView(NoAuthAPIView):
    def get(self, request):
        qs = FontPack.objects.filter(enabled=True).order_by("sort", "-create_time")
        items = [
            {
                "id": item.id,
                "code": item.code,
                "name": item.name,
                "languages": [v for v in item.languages.split(",") if v],
                "version": item.version,
                "license": item.license,
                "source_url": item.source_url,
                "description": item.description,
                "package_url": item.package_url,
                "package_file_name": item.package_file_name,
                "package_size": item.package_size,
                "sha256": item.sha256,
                "download_count": item.download_count,
            }
            for item in qs
        ]
        return Response({"items": items})


class FontPackDownloadView(NoAuthAPIView):
    def get(self, request, code: str):
        pack = FontPack.objects.filter(code=code, enabled=True).first()
        if not pack or not pack.package_url:
            return Response({"detail": "font not found"}, status=404)
        FontPack.objects.filter(id=pack.id).update(
            download_count=pack.download_count + 1
        )
        return HttpResponseRedirect(pack.package_url)

class SyncView(NoAuthAPIView):
    """阅读进度/记录/用户配置：Redis 热数据双向同步。"""

    def get(self, request):
        user, error = get_user(request)
        if error:
            return error
        try:
            since = int(request.query_params.get("since") or 0)
        except (TypeError, ValueError):
            since = 0
        return Response(sync_service.get_sync(user, since))

    def post(self, request):
        user, error = get_user(request)
        if error:
            return error
        data = request.data or {}
        result = sync_service.apply_sync(user, data)
        kinds = [
            kind
            for kind in ("books", "groups", "settings", "progress", "records", "configs")
            if data.get(kind)
        ]
        realtime.notify_sync(user.phone, kinds, str(data.get("device") or ""))
        return Response(result)
