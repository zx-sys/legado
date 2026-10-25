# -*- coding: utf-8 -*-
"""Redis 热数据访问：阅读进度、阅读记录、用户配置项。"""
import logging
import time

from django.conf import settings

logger = logging.getLogger(__name__)

_client = None
_failed_at = 0.0
RETRY_INTERVAL = 60.0

PROGRESS_TTL = 30 * 24 * 60 * 60


def redis_client():
    global _client, _failed_at
    if _client is not None:
        return _client
    if _failed_at and time.time() - _failed_at < RETRY_INTERVAL:
        return None
    url = (getattr(settings, "REDIS_URL", "") or "").rstrip("/")
    if not url:
        return None
    db = int(getattr(settings, "REDIS_DB", 0) or 0)
    try:
        import redis

        client = redis.Redis.from_url(
            f"{url}/{db}",
            decode_responses=True,
            socket_connect_timeout=2,
            socket_timeout=3,
        )
        client.ping()
        _client = client
        return _client
    except Exception as exc:  # pragma: no cover - depends on deployment
        logger.warning("Redis unavailable, sync falls back to MySQL: %s", exc)
        _failed_at = time.time()
        return None


def progress_key(phone: str) -> str:
    return f"legado:u:{phone}:progress"


def records_key(phone: str) -> str:
    return f"legado:u:{phone}:records"


def config_key(phone: str, config_type: str) -> str:
    return f"legado:u:{phone}:config:{config_type}"


def dirty_key(kind: str) -> str:
    return f"legado:dirty:{kind}"
