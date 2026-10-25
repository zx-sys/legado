# -*- coding: utf-8 -*-
"""阅读进度/记录/配置：Redis 热数据 + MySQL 持久化。"""
import json
import logging
import threading
import time

from apps.legado.models import LegadoBook, UserConfig
from apps.legado.services import hot_data

logger = logging.getLogger(__name__)

CONFIG_SOURCE_FILES = {
    "read_config": "readConfig.json",
    "share_read_config": "shareReadConfig.json",
    "theme_config": "themeConfig.json",
    "app_config": "config.xml",
    "video_config": "videoConfig.xml",
}
CONFIG_JSON_TYPES = {"read_config", "share_read_config", "theme_config"}
MAX_CONFIG_BYTES = 2 * 1024 * 1024
MAX_ITEMS_PER_KIND = 2000

FLUSH_INTERVAL = 30


def now_ms() -> int:
    return int(time.time() * 1000)


def _int(value, default=0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def normalize_progress(rows) -> list[dict]:
    result = []
    for row in rows or []:
        if not isinstance(row, dict):
            continue
        book_url = str(row.get("bookUrl") or "").strip()[:700]
        name = str(row.get("name") or "").strip()[:255]
        if not book_url or not name:
            continue
        result.append(
            {
                "bookUrl": book_url,
                "name": name,
                "author": str(row.get("author") or "").strip()[:255],
                "chapterIndex": _int(row.get("chapterIndex")),
                "chapterPos": _int(row.get("chapterPos")),
                "chapterTitle": str(row.get("chapterTitle") or "")[:512],
                # 客户端时钟可能超前, 统一压到服务端当前时间, 避免同步游标被推到未来
                "updatedAt": min(_int(row.get("updatedAt")) or now_ms(), now_ms()),
            }
        )
        if len(result) >= MAX_ITEMS_PER_KIND:
            break
    return result


def normalize_records(rows) -> list[dict]:
    result = []
    for row in rows or []:
        if not isinstance(row, dict):
            continue
        name = str(row.get("bookName") or "").strip()[:255]
        if not name:
            continue
        result.append(
            {
                "bookName": name,
                "author": str(row.get("author") or "").strip()[:255],
                "readTime": _int(row.get("readTime")),
                "lastRead": _int(row.get("lastRead")),
                "lastChapterIndex": _int(row.get("lastChapterIndex"), -1),
                "lastChapterPos": _int(row.get("lastChapterPos")),
                "lastChapterTitle": str(row.get("lastChapterTitle") or "")[:512],
                "updatedAt": min(_int(row.get("updatedAt")) or now_ms(), now_ms()),
            }
        )
        if len(result) >= MAX_ITEMS_PER_KIND:
            break
    return result


def normalize_configs(rows) -> list[dict]:
    result = []
    for row in rows or []:
        if not isinstance(row, dict):
            continue
        config_type = str(row.get("type") or "").strip()
        if config_type not in CONFIG_SOURCE_FILES:
            continue
        content = row.get("content")
        if not isinstance(content, str) or len(content.encode("utf-8")) > MAX_CONFIG_BYTES:
            continue
        result.append(
            {
                "type": config_type,
                "content": content,
                "updatedAt": min(_int(row.get("updatedAt")) or now_ms(), now_ms()),
            }
        )
    return result


def _record_field(row: dict) -> str:
    return f"{row['bookName']}::{row['author']}"


def _dirty_add(client, kind: str, phone: str, field: str, entry: dict) -> None:
    client.hset(hot_data.dirty_key(kind), f"{phone}|{field}", json.dumps(entry, ensure_ascii=False))


# ---------------------------------------------------------------- 读取


def _read_redis(phone: str, since: int) -> dict | None:
    client = hot_data.redis_client()
    if client is None:
        return None
    try:
        progress_rows = client.hvals(hot_data.progress_key(phone))
        record_rows = client.hvals(hot_data.records_key(phone))
        configs = []
        for config_type in CONFIG_SOURCE_FILES:
            raw = client.get(hot_data.config_key(phone, config_type))
            if not raw:
                continue
            data = json.loads(raw)
            data["type"] = config_type
            configs.append(data)
        progress = [
            row for row in (json.loads(item) for item in progress_rows)
            if row.get("updatedAt", 0) > since
        ]
        records = [
            row for row in (json.loads(item) for item in record_rows)
            if row.get("updatedAt", 0) > since
        ]
        configs = [row for row in configs if row.get("updatedAt", 0) > since]
        return {"progress": progress, "records": records, "configs": configs}
    except Exception as exc:
        logger.warning("read sync from redis failed: %s", exc)
        return None


def _read_mysql(user, since: int) -> dict:
    progress = []
    books = LegadoBook.objects.filter(user=user).only(
        "book_url", "name", "author", "dur_chapter_index", "dur_chapter_pos",
        "dur_chapter_title", "dur_chapter_time",
    )
    for book in books:
        ts = int(book.dur_chapter_time or 0)
        if ts <= since:
            continue
        progress.append(
            {
                "bookUrl": book.book_url,
                "name": book.name,
                "author": book.author,
                "chapterIndex": book.dur_chapter_index,
                "chapterPos": book.dur_chapter_pos,
                "chapterTitle": book.dur_chapter_title,
                "updatedAt": ts,
            }
        )
    records = []
    for book in books:
        ts = int(book.last_read_time or 0)
        if ts <= since:
            continue
        records.append(
            {
                "bookName": book.name,
                "author": book.author,
                "readTime": int(book.read_time or 0),
                "lastRead": ts,
                "lastChapterIndex": book.last_chapter_index,
                "lastChapterPos": book.last_chapter_pos,
                "lastChapterTitle": book.last_chapter_title,
                "updatedAt": ts,
            }
        )
    configs = []
    for config in UserConfig.objects.filter(user=user, config_type__in=CONFIG_SOURCE_FILES):
        ts = int(config.update_time.timestamp() * 1000) if config.update_time else 0
        if ts <= since:
            continue
        configs.append(
            {"type": config.config_type, "content": config.content, "updatedAt": ts}
        )
    return {"progress": progress, "records": records, "configs": configs}


def get_sync(user, since: int = 0) -> dict:
    since = max(0, int(since or 0))
    payload = _read_redis(user.phone, since)
    if payload is None:
        payload = _read_mysql(user, since)
    payload["serverTime"] = now_ms()
    return payload


# ---------------------------------------------------------------- 写入


def _merge_redis(phone: str, payload: dict) -> dict:
    client = hot_data.redis_client()
    if client is None:
        return None
    server_newer = {"progress": [], "records": [], "configs": []}
    for row in payload["progress"]:
        key = hot_data.progress_key(phone)
        stored_raw = client.hget(key, row["bookUrl"])
        stored = json.loads(stored_raw) if stored_raw else None
        if stored and stored.get("updatedAt", 0) > row["updatedAt"]:
            server_newer["progress"].append(stored)
            continue
        client.hset(key, row["bookUrl"], json.dumps(row, ensure_ascii=False))
        client.expire(key, hot_data.PROGRESS_TTL)
        _dirty_add(client, "progress", phone, row["bookUrl"], row)
    for row in payload["records"]:
        key = hot_data.records_key(phone)
        field = _record_field(row)
        stored_raw = client.hget(key, field)
        stored = json.loads(stored_raw) if stored_raw else None
        if stored and stored.get("updatedAt", 0) > row["updatedAt"]:
            server_newer["records"].append(stored)
            continue
        if stored:
            row["readTime"] = max(row["readTime"], stored.get("readTime", 0))
        client.hset(key, field, json.dumps(row, ensure_ascii=False))
        client.expire(key, hot_data.PROGRESS_TTL)
        _dirty_add(client, "records", phone, field, row)
    for row in payload["configs"]:
        key = hot_data.config_key(phone, row["type"])
        stored_raw = client.get(key)
        stored = json.loads(stored_raw) if stored_raw else None
        if stored and stored.get("updatedAt", 0) > row["updatedAt"]:
            server_newer["configs"].append(
                {"type": row["type"], "content": stored.get("content", ""),
                 "updatedAt": stored.get("updatedAt", 0)}
            )
            continue
        client.set(key, json.dumps({"content": row["content"], "updatedAt": row["updatedAt"]}))
        _dirty_add(
            client, "config", phone, row["type"],
            {"content": row["content"], "updatedAt": row["updatedAt"]},
        )
    return server_newer


def _merge_mysql(user, payload: dict) -> dict:
    server_newer = {"progress": [], "records": [], "configs": []}
    for row in payload["progress"]:
        book = _book_for(user, row["name"], row["author"], row["bookUrl"])
        if int(book.dur_chapter_time or 0) > row["updatedAt"]:
            server_newer["progress"].append(
                {
                    "bookUrl": book.book_url,
                    "name": book.name,
                    "author": book.author,
                    "chapterIndex": book.dur_chapter_index,
                    "chapterPos": book.dur_chapter_pos,
                    "chapterTitle": book.dur_chapter_title,
                    "updatedAt": int(book.dur_chapter_time or 0),
                }
            )
            continue
        _apply_book_progress(book, row)
    for row in payload["records"]:
        book = _book_for(user, row["bookName"], row["author"], "")
        if int(book.last_read_time or 0) > row["updatedAt"]:
            server_newer["records"].append(
                {
                    "bookName": book.name,
                    "author": book.author,
                    "readTime": int(book.read_time or 0),
                    "lastRead": int(book.last_read_time or 0),
                    "lastChapterIndex": book.last_chapter_index,
                    "lastChapterPos": book.last_chapter_pos,
                    "lastChapterTitle": book.last_chapter_title,
                    "updatedAt": int(book.last_read_time or 0),
                }
            )
            continue
        _apply_book_record(book, row)
    for row in payload["configs"]:
        config = UserConfig.objects.filter(
            user=user, config_type=row["type"]
        ).first()
        stored_ts = int(config.update_time.timestamp() * 1000) if config and config.update_time else 0
        if stored_ts > row["updatedAt"]:
            server_newer["configs"].append(
                {"type": config.config_type, "content": config.content, "updatedAt": stored_ts}
            )
            continue
        _apply_config(user, row["type"], row["content"])
    return server_newer


def apply_sync(user, payload: dict) -> dict:
    payload = {
        "progress": normalize_progress(payload.get("progress")),
        "records": normalize_records(payload.get("records")),
        "configs": normalize_configs(payload.get("configs")),
    }
    server_newer = _merge_redis(user.phone, payload)
    if server_newer is None:
        server_newer = _merge_mysql(user, payload)
    server_newer["serverTime"] = now_ms()
    return server_newer


def _book_for(user, name: str, author: str, book_url: str) -> LegadoBook:
    book = None
    if book_url:
        book = LegadoBook.objects.filter(user=user, book_url=book_url).first()
    if book is None:
        book = LegadoBook.objects.filter(user=user, name=name, author=author).first()
    if book is None:
        book = LegadoBook.objects.create(
            user=user, name=name, author=author, book_url=book_url
        )
    return book


def _apply_book_progress(book: LegadoBook, row: dict) -> None:
    book.book_url = row["bookUrl"] or book.book_url
    book.dur_chapter_index = row["chapterIndex"]
    book.dur_chapter_pos = row["chapterPos"]
    book.dur_chapter_title = row["chapterTitle"]
    book.dur_chapter_time = row["updatedAt"]
    book.save(
        update_fields=[
            "book_url", "dur_chapter_index", "dur_chapter_pos",
            "dur_chapter_title", "dur_chapter_time", "update_time",
        ]
    )


def _apply_book_record(book: LegadoBook, row: dict) -> None:
    book.read_time = max(int(book.read_time or 0), row["readTime"])
    book.last_read_time = row["lastRead"]
    book.last_chapter_index = row["lastChapterIndex"]
    book.last_chapter_pos = row["lastChapterPos"]
    book.last_chapter_title = row["lastChapterTitle"]
    book.save(
        update_fields=[
            "read_time", "last_read_time", "last_chapter_index",
            "last_chapter_pos", "last_chapter_title", "update_time",
        ]
    )


def _apply_config(user, config_type: str, content: str) -> None:
    raw_json = None
    if config_type in CONFIG_JSON_TYPES:
        try:
            raw_json = json.loads(content)
        except json.JSONDecodeError:
            raw_json = None
    UserConfig.objects.update_or_create(
        user=user,
        config_type=config_type,
        defaults={
            "source_file": CONFIG_SOURCE_FILES[config_type],
            "content": content,
            "raw_json": raw_json,
        },
    )


# ---------------------------------------------------------------- 落库


def flush_dirty() -> int:
    client = hot_data.redis_client()
    if client is None:
        return 0
    flushed = 0
    flushed += _flush_progress(client)
    flushed += _flush_records(client)
    flushed += _flush_configs(client)
    return flushed


def _pop_dirty(client, kind: str) -> list[tuple[str, str, dict]]:
    key = hot_data.dirty_key(kind)
    rows = client.hgetall(key)
    if not rows:
        return []
    client.hdel(key, *rows.keys())
    result = []
    for field, raw in rows.items():
        phone, _, item_key = field.partition("|")
        try:
            entry = json.loads(raw)
        except json.JSONDecodeError:
            continue
        result.append((phone, item_key, entry))
    return result


def _flush_progress(client) -> int:
    from apps.legado.models import LegadoUser

    count = 0
    for phone, book_url, entry in _pop_dirty(client, "progress"):
        try:
            user = LegadoUser.objects.filter(phone=phone).first()
            if user is None:
                continue
            book = _book_for(user, entry.get("name", ""), entry.get("author", ""), book_url)
            if int(book.dur_chapter_time or 0) <= entry.get("updatedAt", 0):
                _apply_book_progress(book, entry)
                count += 1
        except Exception as exc:
            logger.warning("flush progress failed: %s", exc)
    return count


def _flush_records(client) -> int:
    from apps.legado.models import LegadoUser

    count = 0
    for phone, _, entry in _pop_dirty(client, "records"):
        try:
            user = LegadoUser.objects.filter(phone=phone).first()
            if user is None:
                continue
            book = _book_for(user, entry.get("bookName", ""), entry.get("author", ""), "")
            if int(book.last_read_time or 0) <= entry.get("updatedAt", 0):
                _apply_book_record(book, entry)
                count += 1
        except Exception as exc:
            logger.warning("flush records failed: %s", exc)
    return count


def _flush_configs(client) -> int:
    from apps.legado.models import LegadoUser

    count = 0
    for phone, config_type, entry in _pop_dirty(client, "config"):
        try:
            user = LegadoUser.objects.filter(phone=phone).first()
            if user is None or config_type not in CONFIG_SOURCE_FILES:
                continue
            _apply_config(user, config_type, entry.get("content", ""))
            count += 1
        except Exception as exc:
            logger.warning("flush config failed: %s", exc)
    return count


_flusher_started = False


def start_flusher() -> None:
    global _flusher_started
    if _flusher_started:
        return
    _flusher_started = True

    def loop():
        # 启动时先落一次, 之后每 30 秒
        while True:
            try:
                flushed = flush_dirty()
                if flushed:
                    logger.info("legado sync flushed %s items", flushed)
            except Exception as exc:  # pragma: no cover
                logger.warning("legado sync flush error: %s", exc)
            time.sleep(FLUSH_INTERVAL)

    threading.Thread(target=loop, name="legado-sync-flusher", daemon=True).start()
