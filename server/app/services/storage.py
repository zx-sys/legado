import logging
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from app.core.config import settings

logger = logging.getLogger(__name__)


class StorageError(RuntimeError):
    pass


@dataclass
class StoredObject:
    storage: str
    object_key: str | None
    local_path: str | None


def safe_relative_path(path: str) -> str:
    normalized = path.replace("\\", "/").lstrip("/")
    pure = PurePosixPath(normalized)
    if not pure.parts or any(part in ("..", "") for part in pure.parts):
        raise StorageError(f"unsafe path: {path}")
    return str(pure)


class StorageService:
    def __init__(self) -> None:
        self._bucket = None
        self._bucket_checked = False

    @property
    def mode(self) -> str:
        return "oss" if settings.oss_enabled else "local"

    def _get_bucket(self):
        if self._bucket_checked:
            return self._bucket
        self._bucket_checked = True
        if not settings.oss_enabled:
            return None
        try:
            import oss2

            auth = oss2.Auth(settings.oss_access_key_id, settings.oss_access_key_secret)
            endpoint = settings.oss_endpoint.strip()
            if not endpoint.startswith(("http://", "https://")):
                endpoint = f"https://{endpoint}"
            self._bucket = oss2.Bucket(auth, endpoint, settings.oss_bucket)
        except Exception as exc:  # pragma: no cover - depends on deployment
            logger.warning("oss init failed: %s", exc)
            self._bucket = None
        return self._bucket

    def put_bytes(
        self,
        phone: str,
        backup_id: int,
        relative_path: str,
        data: bytes,
        content_type: str,
    ) -> StoredObject:
        path = safe_relative_path(relative_path)
        bucket = self._get_bucket()
        if bucket is not None:
            key = "/".join(
                part.strip("/")
                for part in (settings.oss_prefix, phone, str(backup_id), path)
                if part
            )
            try:
                bucket.put_object(key, data, headers={"Content-Type": content_type})
                return StoredObject("oss", key, None)
            except Exception as exc:  # pragma: no cover
                raise StorageError(f"oss upload failed: {exc}") from exc

        local_path = Path(settings.local_storage_dir) / phone / str(backup_id) / path
        local_path.parent.mkdir(parents=True, exist_ok=True)
        local_path.write_bytes(data)
        return StoredObject("local", None, str(local_path))

    def read_bytes(self, storage: str, object_key: str | None, local_path: str | None) -> bytes:
        if storage == "oss":
            bucket = self._get_bucket()
            if bucket is None or not object_key:
                raise StorageError("oss is not configured")
            return bucket.get_object(object_key).read()
        if storage == "local":
            if not local_path:
                raise StorageError("missing local path")
            file = Path(local_path)
            if not file.is_file():
                raise StorageError(f"backup file missing: {local_path}")
            return file.read_bytes()
        raise StorageError(f"unsupported storage: {storage}")

    def delete(self, storage: str, object_key: str | None, local_path: str | None) -> None:
        try:
            if storage == "oss":
                bucket = self._get_bucket()
                if bucket is not None and object_key:
                    bucket.delete_object(object_key)
            elif storage == "local" and local_path:
                Path(local_path).unlink(missing_ok=True)
        except Exception as exc:  # pragma: no cover
            logger.warning("delete stored object failed: %s", exc)

    def public_url(self, object_key: str | None) -> str | None:
        if not object_key:
            return None
        base = settings.oss_public_base_url.rstrip("/")
        if not base:
            return None
        return f"{base}/{object_key.lstrip('/')}"


storage = StorageService()
