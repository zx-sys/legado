# -*- coding: utf-8 -*-
"""向用户设备广播同步变更通知。"""
import logging

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer

logger = logging.getLogger(__name__)


def notify_sync(phone: str, kinds: list[str], source: str = "") -> None:
    if not phone or not kinds:
        return
    try:
        layer = get_channel_layer()
        if layer is None:
            return
        logger.info("legado ws notify: %s %s", phone, kinds)
        async_to_sync(layer.group_send)(
            f"legado:u:{phone}",
            {"type": "sync.changed", "kinds": kinds, "source": source},
        )
    except Exception as exc:  # pragma: no cover - depends on channel layer
        logger.warning("legado ws notify failed: %s", exc)
