import json
import logging
from typing import Any

from app.core.config import settings

logger = logging.getLogger(__name__)


class Cache:
    """Redis 是可选的；未配置或连接失败时自动退化为不走缓存。"""

    def __init__(self) -> None:
        self._client = None
        self._checked = False

    def _get_client(self):
        if self._checked:
            return self._client
        self._checked = True
        if not settings.redis_url:
            return None
        try:
            import redis

            self._client = redis.Redis.from_url(
                settings.redis_url,
                decode_responses=True,
                socket_connect_timeout=0.5,
                socket_timeout=1.0,
            )
        except Exception as exc:  # pragma: no cover - depends on deployment
            logger.warning("redis init failed: %s", exc)
            self._client = None
        return self._client

    def get_json(self, key: str) -> Any | None:
        client = self._get_client()
        if client is None:
            return None
        try:
            raw = client.get(key)
            return json.loads(raw) if raw else None
        except Exception as exc:  # pragma: no cover
            logger.debug("redis get failed: %s", exc)
            return None

    def set_json(self, key: str, value: Any, ttl_seconds: int) -> None:
        client = self._get_client()
        if client is None:
            return
        try:
            client.setex(key, ttl_seconds, json.dumps(value, ensure_ascii=False))
        except Exception as exc:  # pragma: no cover
            logger.debug("redis set failed: %s", exc)

    def delete(self, *keys: str) -> None:
        client = self._get_client()
        if client is None or not keys:
            return
        try:
            client.delete(*keys)
        except Exception as exc:  # pragma: no cover
            logger.debug("redis delete failed: %s", exc)

    def ping(self) -> bool:
        client = self._get_client()
        if client is None:
            return False
        try:
            return bool(client.ping())
        except Exception:
            return False


cache = Cache()


def latest_cache_key(phone: str, device_name: str | None) -> str:
    return f"legado:latest:{phone}:{device_name or '*'}"
