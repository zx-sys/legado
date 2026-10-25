# -*- coding: utf-8 -*-
import hashlib
import logging
from pathlib import Path, PurePosixPath

from django.conf import settings

logger = logging.getLogger(__name__)


class StorageError(RuntimeError):
    pass


def safe_relative_path(path: str) -> str:
    normalized = path.replace("\\", "/").lstrip("/")
    pure = PurePosixPath(normalized)
    if not pure.parts or any(part in ("..", "") for part in pure.parts):
        raise StorageError(f"unsafe path: {path}")
    return str(pure)


class OssStorage:
    """OSS 优先，未配置或上传失败时落本地 media。"""

    def __init__(self):
        self._bucket = None

    @property
    def enabled(self) -> bool:
        return bool(
            getattr(settings, "OSS_ENDPOINT", "")
            and getattr(settings, "OSS_BUCKET", "")
            and getattr(settings, "OSS_ACCESS_KEY_ID", "")
            and getattr(settings, "OSS_ACCESS_KEY_SECRET", "")
        )

    def _get_bucket(self):
        if self._bucket is not None:
            return self._bucket
        if not self.enabled:
            return None
        import oss2

        endpoint = settings.OSS_ENDPOINT.strip()
        if not endpoint.startswith(("http://", "https://")):
            endpoint = f"https://{endpoint}"
        auth = oss2.Auth(settings.OSS_ACCESS_KEY_ID, settings.OSS_ACCESS_KEY_SECRET)
        self._bucket = oss2.Bucket(auth, endpoint, settings.OSS_BUCKET)
        return self._bucket

    def put_bytes(
        self, phone: str, backup_id, relative_path: str, data: bytes, content_type: str
    ):
        path = safe_relative_path(relative_path)
        bucket = self._get_bucket()
        if bucket is not None:
            key = "/".join(
                part.strip("/")
                for part in (settings.OSS_PREFIX, "backups", phone, str(backup_id), path)
                if part
            )
            try:
                bucket.put_object(key, data, headers={"Content-Type": content_type})
                return "oss", key, None
            except Exception as exc:  # pragma: no cover - depends on deployment
                logger.warning("OSS upload failed, fallback local: %s", exc)
        local_path = Path(settings.MEDIA_ROOT) / "legado" / phone / str(backup_id) / path
        local_path.parent.mkdir(parents=True, exist_ok=True)
        local_path.write_bytes(data)
        return "local", None, str(local_path)

    def put_tts_package(self, code: str, filename: str, data: bytes):
        bucket = self._get_bucket()
        safe_name = PurePosixPath(filename).name
        if bucket is not None:
            key = "/".join(
                part.strip("/")
                for part in (settings.OSS_PREFIX, "tts", "engines", code, safe_name)
                if part
            )
            bucket.put_object(
                key, data, headers={"Content-Type": "application/octet-stream"}
            )
            base = (getattr(settings, "OSS_PUBLIC_BASE_URL", "") or "").rstrip("/")
            url = f"{base}/{key}" if base else ""
            return key, url
        local_path = Path(settings.MEDIA_ROOT) / "legado" / "tts" / code / safe_name
        local_path.parent.mkdir(parents=True, exist_ok=True)
        local_path.write_bytes(data)
        rel = local_path.relative_to(settings.MEDIA_ROOT).as_posix()
        base = getattr(settings, "DOMAIN_HOST", "").rstrip("/")
        return "", f"{base}{settings.MEDIA_URL}{rel}"

    def put_font_package(self, code: str, filename: str, data: bytes):
        bucket = self._get_bucket()
        # OSS 地址用 ASCII 文件名, 避免中文名在 Location 头里转义问题
        suffix = PurePosixPath(filename).suffix.lower() or ".ttf"
        safe_name = f"{code}{suffix}"
        if bucket is not None:
            key = "/".join(
                part.strip("/")
                for part in (settings.OSS_PREFIX, "fonts", code, safe_name)
                if part
            )
            bucket.put_object(
                key, data, headers={"Content-Type": "application/octet-stream"}
            )
            base = (getattr(settings, "OSS_PUBLIC_BASE_URL", "") or "").rstrip("/")
            url = f"{base}/{key}" if base else ""
            return key, url
        local_path = Path(settings.MEDIA_ROOT) / "legado" / "fonts" / code / safe_name
        local_path.parent.mkdir(parents=True, exist_ok=True)
        local_path.write_bytes(data)
        rel = local_path.relative_to(settings.MEDIA_ROOT).as_posix()
        base = getattr(settings, "DOMAIN_HOST", "").rstrip("/")
        return "", f"{base}{settings.MEDIA_URL}{rel}"

    def read_bytes(self, storage: str, object_key: str | None, local_path: str | None) -> bytes:
        if storage == "oss":
            bucket = self._get_bucket()
            if bucket is None or not object_key:
                raise StorageError("OSS 未配置")
            return bucket.get_object(object_key).read()
        if storage == "local":
            if not local_path:
                raise StorageError("缺少本地路径")
            path = Path(local_path)
            if not path.is_file():
                raise StorageError(f"文件不存在: {local_path}")
            return path.read_bytes()
        raise StorageError(f"不支持存储类型: {storage}")

    def delete(self, storage: str, object_key: str | None, local_path: str | None) -> None:
        try:
            if storage == "oss":
                bucket = self._get_bucket()
                if bucket is not None and object_key:
                    bucket.delete_object(object_key)
            elif storage == "local" and local_path:
                Path(local_path).unlink(missing_ok=True)
        except Exception as exc:  # pragma: no cover
            logger.warning("delete storage object failed: %s", exc)


storage = OssStorage()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()
