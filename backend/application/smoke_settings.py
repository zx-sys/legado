"""临时冒烟设置：使用 backend/temp/polish_smoke.sqlite3；先 migrate 再 polish_smoke。"""
from .settings import *  # noqa: F401,F403
from .settings import BASE_DIR

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": BASE_DIR / "temp" / "polish_smoke.sqlite3",
        "OPTIONS": {"timeout": 20},
    }
}

CACHES = {
    "default": {
        "BACKEND": "django.core.cache.backends.locmem.LocMemCache",
    }
}

CHANNEL_LAYERS = {
    "default": {
        "BACKEND": "channels.layers.InMemoryChannelLayer",
    }
}
