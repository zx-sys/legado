"""
ASGI config for application project.

It exposes the ASGI callable as a module-level variable named ``application``.

For more information on this file, see
https://docs.djangoproject.com/en/5.1/howto/deployment/asgi/
"""

import os
import django


os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'application.settings')
django.setup()

from django.core.asgi import get_asgi_application

from utils.middleware import JwtAuthMiddleware
from channels.routing import ProtocolTypeRouter, URLRouter
from django.urls import re_path

from apps.legado.ws import LegadoSyncConsumer
from mysystem.views.wsmessage import NotificationConsumer

websocket_urlpatterns = [
    re_path(r'^ws/msg/$', JwtAuthMiddleware(NotificationConsumer.as_asgi())),
    re_path(r'^ws/legado/sync/$', LegadoSyncConsumer.as_asgi()),
]

application = ProtocolTypeRouter({
    "http": get_asgi_application(),
    "websocket": URLRouter(websocket_urlpatterns),
})
