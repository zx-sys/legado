# -*- coding: utf-8 -*-
import hashlib
import json
import logging
import re
import tempfile
import zipfile
from datetime import datetime
from pathlib import Path

from django.conf import settings
from django.db import transaction
from django.utils import timezone

from apps.legado.models import (
    Backup,
    BackupItem,
    BackupRecord,
    BookSource,
    LegadoBook,
    LegadoBookmark,
    LegadoHighlight,
    LegadoUser,
    TtsEngine,
    UserConfig,
)
from apps.legado.services.storage import safe_relative_path, storage

logger = logging.getLogger(__name__)

TEXT_SUFFIXES = {".json", ".xml", ".txt", ".js", ".csv", ".html", ".md"}
JSON_SUFFIX = ".json"
CHUNK = 1024 * 1024

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


class BackupError(RuntimeError):
    pass


def normalize_device_name(name: str | None, file_name: str) -> str:
    candidate = (name or "").strip()
    if not candidate:
        match = re.match(r"backup\d{4}-\d{2}-\d{2}-(.+)\.zip$", file_name, re.I)
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


def _save_upload(uploaded_file) -> tuple[Path, int, str]:
    max_bytes = int(getattr(settings, "LEGADO_MAX_BACKUP_MB", 512)) * 1024 * 1024
    tmp_dir = Path(settings.MEDIA_ROOT) / "legado" / ".uploads"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    hasher = hashlib.sha256()
    size = 0
    tmp = tempfile.NamedTemporaryFile(dir=tmp_dir, suffix=".zip", delete=False)
    try:
        uploaded_file.seek(0)
        for chunk in uploaded_file.chunks():
            size += len(chunk)
            if size > max_bytes:
                raise BackupError(f"备份超过 {settings.LEGADO_MAX_BACKUP_MB}MB")
            hasher.update(chunk)
            tmp.write(chunk)
    finally:
        tmp.close()
    return Path(tmp.name), size, hasher.hexdigest()


def create_backup(
    user: LegadoUser,
    uploaded_file,
    device_name: str | None = None,
    trigger: str = "manual",
    keep_latest: bool | None = None,
):
    file_name = Path(uploaded_file.name or "backup.zip").name
    device = normalize_device_name(device_name, file_name)
    path, size, digest = _save_upload(uploaded_file)
    try:
        existing = (
            Backup.objects.filter(
                user=user, device_name=device, sha256=digest, status="ready"
            )
            .order_by("-create_time")
            .first()
        )
        if existing:
            return existing, False
        backup = Backup.objects.create(
            user=user,
            device_name=device,
            file_name=file_name,
            trigger=trigger if trigger in ("auto", "manual") else "manual",
            status="ready",
            sha256=digest,
            size_bytes=size,
        )
        try:
            with transaction.atomic():
                _parse_zip(user, backup, path)
                should_keep = (
                    getattr(settings, "LEGADO_KEEP_LATEST_BACKUP", False)
                    if keep_latest is None
                    else keep_latest
                )
                if should_keep:
                    old = Backup.objects.filter(
                        user=user, device_name=device
                    ).exclude(id=backup.id)
                    for item in old:
                        _delete_storage(item)
                    old.delete()
            return backup, True
        except Exception:
            backup.delete()
            raise
    finally:
        path.unlink(missing_ok=True)


def _parse_zip(user: LegadoUser, backup: Backup, zip_path: Path) -> None:
    max_uncompressed = int(getattr(settings, "LEGADO_MAX_BACKUP_MB", 512)) * 1024 * 1024 * 4
    total = 0
    items = []
    records = []
    datasets = {}
    bookshelf_rows = None
    with zipfile.ZipFile(zip_path) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            raw_name = info.filename.replace("\\", "/")
            if raw_name.startswith("__MACOSX/") or Path(raw_name).name.startswith("._"):
                continue
            path = safe_relative_path(raw_name)
            total += info.file_size
            if total > max_uncompressed:
                raise BackupError("解压后的备份过大")
            data = archive.read(info)
            digest = hashlib.sha256(data).hexdigest()
            ctype = content_type_for(path)
            if is_text_path(path):
                text = data.decode("utf-8-sig", errors="replace")
                items.append(
                    BackupItem(
                        backup=backup,
                        path=path,
                        content_type=ctype,
                        storage="db",
                        content=text,
                        size_bytes=len(data),
                        sha256=digest,
                    )
                )
                _upsert_user_config(user, backup, path, text)
                if path.lower().endswith(JSON_SUFFIX):
                    try:
                        parsed = json.loads(text)
                    except json.JSONDecodeError:
                        parsed = None
                    if isinstance(parsed, list):
                        dataset = Path(path).stem
                        datasets[dataset] = parsed
                        if dataset == "bookshelf":
                            bookshelf_rows = parsed
                        records.extend(
                            BackupRecord(
                                backup=backup,
                                user=user,
                                dataset=dataset,
                                external_id=external_id_for(dataset, row),
                                payload=row,
                            )
                            for row in parsed
                        )
            else:
                kind, key, local_path = storage.put_bytes(
                    user.phone, backup.id, path, data, ctype
                )
                items.append(
                    BackupItem(
                        backup=backup,
                        path=path,
                        content_type=ctype,
                        storage=kind,
                        object_key=key,
                        local_path=local_path,
                        size_bytes=len(data),
                        sha256=digest,
                    )
                )
    BackupItem.objects.bulk_create(items, batch_size=200)
    BackupRecord.objects.bulk_create(records, batch_size=500)
    backup.item_count = len(items)
    backup.save(update_fields=["item_count", "update_time"])
    if bookshelf_rows is not None:
        _upsert_books(user, backup, bookshelf_rows)
    _upsert_annotations(user, datasets)
    _upsert_read_records(user, datasets.get("readRecord"))


CONFIG_FILE_TYPES = {
    "readConfig.json": "read_config",
    "shareReadConfig.json": "share_read_config",
    "themeConfig.json": "theme_config",
    "config.xml": "app_config",
    "videoConfig.xml": "video_config",
}


def _upsert_user_config(
    user: LegadoUser, backup: Backup, path: str, text: str
) -> None:
    config_type = CONFIG_FILE_TYPES.get(Path(path).name)
    if not config_type:
        return
    raw_json = None
    if path.lower().endswith(JSON_SUFFIX):
        try:
            raw_json = json.loads(text)
        except json.JSONDecodeError:
            raw_json = None
    UserConfig.objects.update_or_create(
        user=user,
        config_type=config_type,
        defaults={
            "last_backup": backup,
            "source_file": Path(path).name,
            "content": text,
            "raw_json": raw_json,
        },
    )


def _upsert_books(user: LegadoUser, backup: Backup, rows) -> None:
    for row in rows:
        if not isinstance(row, dict):
            continue
        name = str(row.get("name") or "").strip()
        if not name:
            continue
        author = str(row.get("author") or "").strip()
        defaults = {
            "book_url": str(row.get("bookUrl") or ""),
            "origin": str(row.get("origin") or ""),
            "origin_name": str(row.get("originName") or ""),
            "kind": str(row.get("kind") or ""),
            "group": str(row.get("group") or ""),
            "cover_url": str(row.get("coverUrl") or ""),
            "intro": str(row.get("intro") or ""),
            "latest_chapter_title": str(row.get("latestChapterTitle") or ""),
            "dur_chapter_index": int(row.get("durChapterIndex") or 0),
            "dur_chapter_pos": int(row.get("durChapterPos") or 0),
            "dur_chapter_title": str(row.get("durChapterTitle") or ""),
            "dur_chapter_time": int(row.get("durChapterTime") or 0),
            "total_chapter_num": int(row.get("totalChapterNum") or 0),
            "read_time": int(row.get("readTime") or 0),
            "last_backup": backup,
        }
        LegadoBook.objects.update_or_create(
            user=user, name=name, author=author, defaults=defaults
        )


def _find_book(user: LegadoUser, row: dict, use_url: bool = False):
    if use_url:
        book_url = str(row.get("bookUrl") or "").strip()
        if book_url:
            book = LegadoBook.objects.filter(user=user, book_url=book_url).first()
            if book:
                return book
    name = str(row.get("bookName") or row.get("name") or "").strip()
    author = str(row.get("bookAuthor") or row.get("author") or "").strip()
    if not name:
        return None
    return LegadoBook.objects.filter(user=user, name=name, author=author).first()


def _upsert_annotations(user: LegadoUser, datasets: dict) -> None:
    """书签/标注是书架书籍的子数据，快照中出现对应文件时整体替换。"""
    if "bookmark" in datasets:
        LegadoBookmark.objects.filter(book__user=user).delete()
        bookmarks = []
        seen = set()
        for row in datasets["bookmark"]:
            if not isinstance(row, dict):
                continue
            book = _find_book(user, row)
            if not book:
                continue
            time = int(row.get("time") or 0)
            if (book.id, time) in seen:
                continue
            seen.add((book.id, time))
            bookmarks.append(
                LegadoBookmark(
                    book=book,
                    time=time,
                    chapter_index=int(row.get("chapterIndex") or 0),
                    chapter_pos=int(row.get("chapterPos") or 0),
                    chapter_name=str(row.get("chapterName") or ""),
                    book_text=str(row.get("bookText") or ""),
                    content=str(row.get("content") or ""),
                )
            )
        LegadoBookmark.objects.bulk_create(bookmarks, batch_size=500, ignore_conflicts=True)

    if "highlight" in datasets:
        LegadoHighlight.objects.filter(book__user=user).delete()
        highlights = []
        seen = set()
        for row in datasets["highlight"]:
            if not isinstance(row, dict):
                continue
            book = _find_book(user, row, use_url=True)
            if not book:
                continue
            time = int(row.get("time") or 0)
            if (book.id, time) in seen:
                continue
            seen.add((book.id, time))
            highlights.append(
                LegadoHighlight(
                    book=book,
                    time=time,
                    chapter_url=str(row.get("chapterUrl") or ""),
                    book_url=str(row.get("bookUrl") or ""),
                    chapter_index=int(row.get("chapterIndex") or 0),
                    chapter_pos=int(row.get("chapterPos") or 0),
                    chapter_pos_end=int(row.get("chapterPosEnd") or 0),
                    layout_title_length=int(row.get("layoutTitleLength") or -1),
                    chapter_name=str(row.get("chapterName") or ""),
                    book_text=str(row.get("bookText") or ""),
                    style=str(row.get("style") or ""),
                    note=str(row.get("note") or ""),
                )
            )
        LegadoHighlight.objects.bulk_create(
            highlights, batch_size=500, ignore_conflicts=True
        )


def _upsert_read_records(user: LegadoUser, rows) -> None:
    if not rows:
        return
    for row in rows:
        if not isinstance(row, dict):
            continue
        book = _find_book(user, row)
        if not book:
            continue
        book.read_time = int(row.get("readTime") or 0)
        book.last_read_time = int(row.get("lastRead") or 0)
        book.last_chapter_index = int(row.get("lastChapterIndex") or -1)
        book.last_chapter_pos = int(row.get("lastChapterPos") or 0)
        book.last_chapter_title = str(row.get("lastChapterTitle") or "")
        book.save(
            update_fields=[
                "read_time",
                "last_read_time",
                "last_chapter_index",
                "last_chapter_pos",
                "last_chapter_title",
                "update_time",
            ]
        )


def _delete_storage(backup: Backup) -> None:
    for item in backup.items.all():
        storage.delete(item.storage, item.object_key, item.local_path)


def restore_backup_to_modules(backup: Backup) -> dict:
    """把某份快照覆盖到当前书架/用户配置模块。"""
    user = backup.user
    books_item = backup.items.filter(path="bookshelf.json").first()
    config_items = list(
        backup.items.filter(path__in=list(CONFIG_FILE_TYPES.keys()))
    )
    restored_books = 0
    restored_configs = 0
    with transaction.atomic():
        if books_item is not None:
            rows = backup.records.filter(dataset="bookshelf").values_list(
                "payload", flat=True
            )
            LegadoBook.objects.filter(user=user).delete()
            for row in rows:
                if isinstance(row, dict) and str(row.get("name") or "").strip():
                    _upsert_books(user, backup, [row])
                    restored_books += 1
        datasets = {}
        for filename, dataset in (
            ("bookmark.json", "bookmark"),
            ("highlight.json", "highlight"),
            ("readRecord.json", "readRecord"),
        ):
            item = backup.items.filter(path=filename).first()
            if item is not None:
                try:
                    datasets[dataset] = json.loads(item.content or "[]")
                except json.JSONDecodeError:
                    datasets[dataset] = []
        _upsert_annotations(user, datasets)
        _upsert_read_records(user, datasets.get("readRecord"))
        if config_items:
            types = [CONFIG_FILE_TYPES[item.path] for item in config_items]
            UserConfig.objects.filter(user=user, config_type__in=types).delete()
            for item in config_items:
                _upsert_user_config(user, backup, item.path, item.content or "")
                restored_configs += 1
    return {
        "books": restored_books,
        "configs": restored_configs,
        "bookmarks": LegadoBookmark.objects.filter(book__user=user).count()
        if "bookmark" in datasets else 0,
        "highlights": LegadoHighlight.objects.filter(book__user=user).count()
        if "highlight" in datasets else 0,
        "backup_id": backup.id,
    }


def delete_backup(backup: Backup) -> None:
    _delete_storage(backup)
    backup.delete()


def build_backup_zip(backup: Backup) -> Path:
    target_dir = Path(settings.MEDIA_ROOT) / "legado" / ".downloads"
    target_dir.mkdir(parents=True, exist_ok=True)
    target = Path(tempfile.mkstemp(dir=target_dir, suffix=".zip")[1])
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for item in backup.items.all():
            if item.storage == "db":
                archive.writestr(item.path, (item.content or "").encode("utf-8"))
            else:
                archive.writestr(
                    item.path,
                    storage.read_bytes(item.storage, item.object_key, item.local_path),
                )
    return target


def import_book_sources(data: list | dict) -> tuple[int, int]:
    rows = data if isinstance(data, list) else [data]
    created = updated = 0
    for row in rows:
        if not isinstance(row, dict):
            continue
        name = str(row.get("bookSourceName") or "").strip()
        url = str(row.get("bookSourceUrl") or "").strip()
        if not name or not url:
            continue
        defaults = {
            "name": name,
            "group": str(row.get("bookSourceGroup") or ""),
            "source_type": int(row.get("bookSourceType") or 0),
            "enabled": bool(row.get("enabled", True)),
            "weight": int(row.get("weight") or 0),
            "search_url": str(row.get("searchUrl") or ""),
            "comment": str(row.get("bookSourceComment") or ""),
            "raw_json": row,
        }
        obj, is_created = BookSource.objects.update_or_create(
            source_url=url, defaults=defaults
        )
        created += int(is_created)
        updated += int(not is_created)
    return created, updated


def seed_default_tts_engine() -> TtsEngine:
    obj, _ = TtsEngine.objects.update_or_create(
        code="local-neural-tts",
        defaults={
            "name": "本地中英双语 TTS",
            "engine_type": "sherpa_onnx",
            "languages": "zh,en",
            "speaker_count": 1,
            "version": "v3.0",
            "license": "Model: vits-melo-tts-zh_en",
            "source_url": "https://github.com/k2-fsa/sherpa-onnx",
            "description": "sherpa-onnx + vits-melo-tts-zh_en，离线中英混读，单女声。",
            "package_url": "https://hgcc-test.oss-cn-hangzhou.aliyuncs.com/CourseStandards/legado/tts/vits-melo-tts-zh_en.tar.bz2",
            "package_file_name": "vits-melo-tts-zh_en.tar.bz2",
            "package_size": 167006755,
            "enabled": True,
            "is_default": True,
            "sort": 1,
        },
    )
    return obj
