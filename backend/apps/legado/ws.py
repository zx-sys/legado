# -*- coding: utf-8 -*-
"""App 端书架/进度/设置变更的 WebSocket 通知消费者。"""
import logging
import re
from urllib.parse import parse_qs

logger = logging.getLogger(__name__)

from channels.db import database_sync_to_async
from channels.generic.websocket import AsyncJsonWebsocketConsumer

PHONE_RE = re.compile(r"^\+?\d{6,20}$")


def _normalize_phone(raw: str | None) -> str | None:
    if not raw:
        return None
    phone = re.sub(r"[\s\-()]", "", raw.strip())
    return phone if PHONE_RE.fullmatch(phone) else None


@database_sync_to_async
def _user_exists(phone: str) -> bool:
    from apps.legado.models import LegadoUser

    return LegadoUser.objects.filter(phone=phone).exists()


def _scope_phone(scope) -> str | None:
    headers = dict(scope.get("headers") or [])
    raw = headers.get(b"x-user-phone", b"").decode("utf-8", "ignore")
    if not raw:
        query = parse_qs((scope.get("query_string") or b"").decode("utf-8", "ignore"))
        raw = (query.get("phone") or [""])[0]
    return _normalize_phone(raw)


class LegadoSyncConsumer(AsyncJsonWebsocketConsumer):

    group_name: str | None = None

    async def connect(self):
        phone = _scope_phone(self.scope)
        if not phone:
            await self.close(code=4401)
            return
        if not await _user_exists(phone):
            await self.close(code=4404)
            return
        self.group_name = f"legado:u:{phone}"
        await self.channel_layer.group_add(self.group_name, self.channel_name)
        logger.info("legado ws connected: %s", phone)
        await self.accept()
        await self.send_json({"type": "ready"})

    async def disconnect(self, close_code):
        if self.group_name:
            logger.info("legado ws disconnected: %s (%s)", self.group_name, close_code)
            await self.channel_layer.group_discard(self.group_name, self.channel_name)

    async def receive_json(self, content, **kwargs):
        if isinstance(content, dict) and content.get("type") == "ping":
            await self.send_json({"type": "pong"})

    async def sync_changed(self, event):
        await self.send_json(
            {
                "type": "sync",
                "kinds": event.get("kinds") or [],
                "source": event.get("source") or "",
            }
        )
