import hashlib
import json
import logging
import re
import tempfile
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from fastapi import UploadFile
from sqlalchemy.orm import Session

from app.core.config import settings
from app.models import Backup, BackupItem, BackupRecord, User
from app.services.storage import StorageError, safe_relative_path, storage

logger = logging.getLogger(__name__)

TEXT_SUFFIXES = {".json", ".xml", ".txt", ".js", ".csv", ".html", ".md"}
JSON_SUFFIX = ".json"
CHUNK_SIZE = 1024 * 1024

EXTERNAL_ID_FIELDS = {
    "bookshelf": ("bookUrl",),
    "bookSource": ("bookSourceUrl",),
    "rssSource": ("url",),
    "rssSources": ("url",),
    "readRecord": ("name", "author"),
    "searchHistory": ("keyword",),
    "bookmark": ("bookName", "chapterIndex"),
    "highlight": ("bookName", "chapterIndex"),
    "bookGroup": ("groupId", "groupName"),
    "replaceRule": ("name",),
}


@dataclass
class UploadResult:
    backup: Backup
    created: bool


def normalize_device_name(name: str | None, file_name: str) -> str:
    candidate = (name or "").strip()
    if not candidate:
        match = re.match(r"backup\d{4}-\d{2}-\d{2}-(.+)\.zip$", file_name, re.IGNORECASE)
        candidate = match.group(1) if match else "default"
    candidate = re.sub(r"[\r\n\t]+", " ", candidate).strip()
    return candidate[:128] or "default"


def content_type_for(path: str) -> str:
    suffix = Path(path).suffix.lower()
    if suffix == JSON_SUFFIX:
        return "application/json"
    if suffix == ".xml":
        return "application/xml"
    if suffix in TEXT_SUFFIXES:
        return "text/plain"
    if suffix in {".jpg", ".jpeg"}:
        return "image/jpeg"
    if suffix == ".png":
        return "image/png"
    if suffix == ".webp":
        return "image/webp"
    if suffix == ".gif":
        return "image/gif"
    if suffix == ".ttf":
        return "font/ttf"
    if suffix == ".otf":
        return "font/otf"
    return "application/octet-stream"


def is_text_path(path: str) -> bool:
    return Path(path).suffix.lower() in TEXT_SUFFIXES


def external_id_for(dataset: str, row) -> str | None:
    if not isinstance(row, dict):
        return None
    parts = []
    for field in EXTERNAL_ID_FIELDS.get(dataset, ()):
        value = row.get(field)
        if value is not None and str(value).strip():
            parts.append(str(value).strip())
    return "::".join(parts)[:512] if parts else None


def _save_upload(upload: UploadFile) -> tuple[Path, int, str]:
    max_bytes = settings.max_backup_mb * 1024 * 1024
    upload_dir = Path(settings.local_storage_dir) / ".uploads"
    upload_dir.mkdir(parents=True, exist_ok=True)
    hasher = hashlib.sha256()
    size = 0
    tmp = tempfile.NamedTemporaryFile(dir=upload_dir, suffix=".zip", delete=False)
    try:
        while chunk := upload.file.read(CHUNK_SIZE):
            size += len(chunk)
            if size > max_bytes:
                raise ValueError(f"backup exceeds {settings.max_backup_mb}MB")
            hasher.update(chunk)
            tmp.write(chunk)
    except Exception:
        tmp.close()
        Path(tmp.name).unlink(missing_ok=True)
        raise
    tmp.close()
    return Path(tmp.name), size, hasher.hexdigest()


def _parse_zip(db: Session, user: User, backup: Backup, zip_path: Path) -> None:
    max_uncompressed = settings.max_backup_mb * 1024 * 1024 * 4
    total_uncompressed = 0
    items: list[BackupItem] = []
    with zipfile.ZipFile(zip_path) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            raw_name = info.filename.replace("\\", "/")
            if raw_name.startswith("__MACOSX/") or Path(raw_name).name.startswith("._"):
                continue
            path = safe_relative_path(raw_name)
            total_uncompressed += info.file_size
            if total_uncompressed > max_uncompressed:
                raise ValueError("uncompressed backup is too large")
            data = archive.read(info)
            digest = hashlib.sha256(data).hexdigest()
            content_type = content_type_for(path)

            if is_text_path(path):
                text = data.decode("utf-8-sig", errors="replace")
                item = BackupItem(
                    backup_id=backup.id,
                    path=path,
                    content_type=content_type,
                    storage="db",
                    content=text,
                    size_bytes=len(data),
                    sha256=digest,
                )
                items.append(item)
                if path.lower().endswith(JSON_SUFFIX):
                    _extract_records(db, user, backup, path, text)
            else:
                stored = storage.put_bytes(
                    user.phone, backup.id, path, data, content_type
                )
                items.append(
                    BackupItem(
                        backup_id=backup.id,
                        path=path,
                        content_type=content_type,
                        storage=stored.storage,
                        object_key=stored.object_key,
                        local_path=stored.local_path,
                        size_bytes=len(data),
                        sha256=digest,
                    )
                )
    db.add_all(items)
    backup.item_count = len(items)


def _extract_records(db: Session, user: User, backup: Backup, path: str, text: str) -> None:
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        return
    if not isinstance(parsed, list):
        return
    dataset = Path(path).stem
    for row in parsed:
        db.add(
            BackupRecord(
                backup_id=backup.id,
                user_id=user.id,
                dataset=dataset,
                external_id=external_id_for(dataset, row),
                payload=row,
            )
        )


def create_backup(
    db: Session,
    user: User,
    upload: UploadFile,
    *,
    device_name: str | None,
    trigger: str,
    keep_latest: bool | None,
) -> UploadResult:
    file_name = Path(upload.filename or "backup.zip").name
    device = normalize_device_name(device_name, file_name)
    zip_path, size, digest = _save_upload(upload)
    try:
        existing = (
            db.query(Backup)
            .filter(
                Backup.user_id == user.id,
                Backup.device_name == device,
                Backup.sha256 == digest,
                Backup.status == "ready",
            )
            .order_by(Backup.created_at.desc())
            .first()
        )
        if existing:
            return UploadResult(backup=existing, created=False)

        now = datetime.now(timezone.utc).replace(tzinfo=None)
        backup: Backup | None = None
        backup = Backup(
            user_id=user.id,
            device_name=device,
            file_name=file_name,
            trigger=trigger if trigger in {"auto", "manual"} else "manual",
            status="ready",
            sha256=digest,
            size_bytes=size,
            item_count=0,
            created_at=now,
            updated_at=now,
        )
        db.add(backup)
        db.flush()
        _parse_zip(db, user, backup, zip_path)

        should_keep_latest = settings.keep_latest_backups if keep_latest is None else keep_latest
        if should_keep_latest:
            old_backups = (
                db.query(Backup)
                .filter(
                    Backup.user_id == user.id,
                    Backup.device_name == device,
                    Backup.id != backup.id,
                )
                .all()
            )
            for old in old_backups:
                _delete_stored_items(old)
                db.delete(old)
        db.commit()
        db.refresh(backup)
        return UploadResult(backup=backup, created=True)
    except Exception as exc:
        db.rollback()
        logger.exception("backup upload failed")
        if backup is not None and backup.id is not None:
            for item in backup.items:
                storage.delete(item.storage, item.object_key, item.local_path)
        raise exc
    finally:
        zip_path.unlink(missing_ok=True)


def _delete_stored_items(backup: Backup) -> None:
    for item in backup.items:
        storage.delete(item.storage, item.object_key, item.local_path)


def delete_backup(db: Session, backup: Backup) -> None:
    _delete_stored_items(backup)
    db.delete(backup)
    db.commit()


def build_backup_zip(db: Session, backup: Backup) -> Path:
    target_dir = Path(settings.local_storage_dir) / ".downloads"
    target_dir.mkdir(parents=True, exist_ok=True)
    target = Path(tempfile.mkstemp(dir=target_dir, suffix=".zip")[1])
    try:
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
            for item in backup.items:
                if item.storage == "db":
                    if item.content is None:
                        continue
                    archive.writestr(item.path, item.content.encode("utf-8"))
                else:
                    data = storage.read_bytes(item.storage, item.object_key, item.local_path)
                    archive.writestr(item.path, data)
    except Exception:
        target.unlink(missing_ok=True)
        raise
    return target
