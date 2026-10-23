import json
import time
from pathlib import Path

from fastapi import APIRouter, BackgroundTasks, Depends, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, Response
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.api.deps import get_current_user
from app.core.cache import cache, latest_cache_key
from app.core.config import settings
from app.core.database import get_db
from app.models import Backup, BackupItem, BackupRecord, User
from app.schemas.backup import (
    BackupDetail,
    BackupItemOut,
    BackupListOut,
    BackupOut,
    BackupRecordOut,
    BackupUploadOut,
)
from app.services import backup_service
from app.services.storage import StorageError, storage

router = APIRouter(prefix="/backups", tags=["backups"])


def _remove_temp_file(path: Path) -> None:
    for _ in range(10):
        try:
            path.unlink(missing_ok=True)
            return
        except PermissionError:
            time.sleep(0.2)


def _owned_backup(db: Session, user: User, backup_id: int) -> Backup:
    backup = (
        db.query(Backup)
        .filter(Backup.id == backup_id, Backup.user_id == user.id)
        .first()
    )
    if backup is None:
        raise HTTPException(status_code=404, detail="backup not found")
    return backup


def _invalidate_latest(user: User, device_name: str) -> None:
    cache.delete(latest_cache_key(user.phone, device_name), latest_cache_key(user.phone, None))


def _detail(db: Session, backup: Backup) -> BackupDetail:
    counts = dict(
        db.query(BackupRecord.dataset, func.count(BackupRecord.id))
        .filter(BackupRecord.backup_id == backup.id)
        .group_by(BackupRecord.dataset)
        .all()
    )
    return BackupDetail(
        **BackupOut.model_validate(backup).model_dump(),
        items=[BackupItemOut.model_validate(item) for item in backup.items],
        datasets=counts,
    )


@router.post("/upload", response_model=BackupUploadOut)
def upload_backup(
    file: UploadFile = File(...),
    device_name: str | None = Form(default=None),
    trigger: str = Form(default="manual"),
    keep_latest: bool | None = Form(default=None),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> BackupUploadOut:
    if not (file.filename or "").lower().endswith(".zip"):
        raise HTTPException(status_code=400, detail="only zip backups are supported")
    try:
        result = backup_service.create_backup(
            db,
            user,
            file,
            device_name=device_name,
            trigger=trigger,
            keep_latest=keep_latest,
        )
    except ValueError as exc:
        raise HTTPException(status_code=413, detail=str(exc)) from exc
    except StorageError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    _invalidate_latest(user, result.backup.device_name)
    return BackupUploadOut(
        created=result.created,
        backup=BackupOut.model_validate(result.backup),
    )


@router.get("", response_model=BackupListOut)
def list_backups(
    device_name: str | None = Query(default=None),
    limit: int = Query(default=20, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> BackupListOut:
    query = db.query(Backup).filter(Backup.user_id == user.id)
    if device_name:
        query = query.filter(Backup.device_name == device_name)
    total = query.count()
    items = (
        query.order_by(Backup.created_at.desc())
        .offset(offset)
        .limit(limit)
        .all()
    )
    return BackupListOut(total=total, items=[BackupOut.model_validate(item) for item in items])


@router.get("/latest", response_model=BackupOut)
def latest_backup(
    device_name: str | None = Query(default=None),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> BackupOut:
    key = latest_cache_key(user.phone, device_name)
    cached = cache.get_json(key)
    if cached:
        return BackupOut(**cached)
    query = db.query(Backup).filter(
        Backup.user_id == user.id, Backup.status == "ready"
    )
    if device_name:
        query = query.filter(Backup.device_name == device_name)
    backup = query.order_by(Backup.created_at.desc()).first()
    if backup is None:
        raise HTTPException(status_code=404, detail="backup not found")
    result = BackupOut.model_validate(backup)
    cache.set_json(key, json.loads(result.model_dump_json()), settings.latest_cache_seconds)
    return result


@router.get("/{backup_id}", response_model=BackupDetail)
def get_backup(
    backup_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> BackupDetail:
    return _detail(db, _owned_backup(db, user, backup_id))


@router.get("/{backup_id}/download")
def download_backup(
    backup_id: int,
    background: BackgroundTasks,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> FileResponse:
    backup = _owned_backup(db, user, backup_id)
    try:
        path = backup_service.build_backup_zip(db, backup)
    except StorageError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    background.add_task(_remove_temp_file, path)
    return FileResponse(
        path,
        media_type="application/zip",
        filename=backup.file_name or f"backup-{backup.id}.zip",
    )


@router.get("/{backup_id}/items/{item_path:path}")
def get_backup_item(
    backup_id: int,
    item_path: str,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> Response:
    backup = _owned_backup(db, user, backup_id)
    item = (
        db.query(BackupItem)
        .filter(BackupItem.backup_id == backup.id, BackupItem.path == item_path)
        .first()
    )
    if item is None:
        raise HTTPException(status_code=404, detail="backup item not found")
    if item.storage == "db":
        content = (item.content or "").encode("utf-8")
    else:
        try:
            content = storage.read_bytes(item.storage, item.object_key, item.local_path)
        except StorageError as exc:
            raise HTTPException(status_code=502, detail=str(exc)) from exc
    return Response(content=content, media_type=item.content_type)


@router.get("/{backup_id}/records", response_model=list[BackupRecordOut])
def get_backup_records(
    backup_id: int,
    dataset: str | None = Query(default=None),
    limit: int = Query(default=200, ge=1, le=2000),
    offset: int = Query(default=0, ge=0),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> list[BackupRecord]:
    backup = _owned_backup(db, user, backup_id)
    query = db.query(BackupRecord).filter(BackupRecord.backup_id == backup.id)
    if dataset:
        query = query.filter(BackupRecord.dataset == dataset)
    return query.order_by(BackupRecord.id).offset(offset).limit(limit).all()


@router.delete("/{backup_id}")
def remove_backup(
    backup_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> dict[str, bool]:
    backup = _owned_backup(db, user, backup_id)
    device_name = backup.device_name
    backup_service.delete_backup(db, backup)
    _invalidate_latest(user, device_name)
    return {"deleted": True}
