import io
import os
import tempfile
import zipfile
from pathlib import Path

import pytest

TEST_ROOT = Path(tempfile.mkdtemp(prefix="legado-server-test-"))
os.environ.update(
    DATABASE_URL=f"sqlite:///{TEST_ROOT / 'test.db'}",
    LOCAL_STORAGE_DIR=str(TEST_ROOT / "storage"),
    REDIS_URL="",
    OSS_ENDPOINT="",
    OSS_BUCKET="",
    OSS_ACCESS_KEY_ID="",
    OSS_ACCESS_KEY_SECRET="",
    DEEPSEEK_API_KEY="",
    MAX_BACKUP_MB="10",
    KEEP_LATEST_BACKUPS="true",
)

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402


@pytest.fixture(scope="session")
def client():
    with TestClient(app) as test_client:
        yield test_client


def auth(phone: str) -> dict[str, str]:
    return {"X-User-Phone": phone}


def make_backup_zip(extra: bool = False) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(
            "bookshelf.json",
            '[{"bookUrl":"https://example.com/book/1","name":"测试书","author":"作者"}]',
        )
        archive.writestr(
            "bookSource.json",
            '[{"bookSourceUrl":"https://example.com","bookSourceName":"示例源"}]',
        )
        archive.writestr("config.xml", "<map><string name=\"themeMode\">0</string></map>")
        archive.writestr("covers/cover.jpg", b"\xff\xd8\xff\xe0fake-jpeg")
        if extra:
            archive.writestr("searchHistory.json", '["关键词"]')
    return buffer.getvalue()
