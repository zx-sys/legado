import io
import zipfile

from tests.conftest import auth, make_backup_zip


def upload(client, phone: str, content: bytes, device: str = "test-phone", keep_latest: bool = True):
    return client.post(
        "/api/v1/backups/upload",
        headers=auth(phone),
        files={"file": (f"backup2026-10-22-{device}.zip", content, "application/zip")},
        data={
            "device_name": device,
            "trigger": "auto",
            "keep_latest": str(keep_latest).lower(),
        },
    )


def test_upload_list_download_and_isolation(client):
    phone = "13800000001"
    response = upload(client, phone, make_backup_zip())
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["created"] is True
    backup = payload["backup"]
    assert backup["device_name"] == "test-phone"
    assert backup["item_count"] == 4

    latest = client.get("/api/v1/backups/latest", headers=auth(phone))
    assert latest.status_code == 200
    assert latest.json()["id"] == backup["id"]

    listing = client.get("/api/v1/backups", headers=auth(phone))
    assert listing.status_code == 200
    assert listing.json()["total"] == 1

    detail = client.get(f"/api/v1/backups/{backup['id']}", headers=auth(phone))
    assert detail.status_code == 200
    assert detail.json()["datasets"]["bookshelf"] == 1
    assert detail.json()["datasets"]["bookSource"] == 1

    records = client.get(
        f"/api/v1/backups/{backup['id']}/records",
        headers=auth(phone),
        params={"dataset": "bookshelf"},
    )
    assert records.status_code == 200
    assert records.json()[0]["payload"]["name"] == "测试书"

    item = client.get(
        f"/api/v1/backups/{backup['id']}/items/bookshelf.json", headers=auth(phone)
    )
    assert item.status_code == 200
    assert "测试书" in item.text

    archive_response = client.get(
        f"/api/v1/backups/{backup['id']}/download", headers=auth(phone)
    )
    assert archive_response.status_code == 200
    with zipfile.ZipFile(io.BytesIO(archive_response.content)) as archive:
        assert set(archive.namelist()) == {
            "bookshelf.json",
            "bookSource.json",
            "config.xml",
            "covers/cover.jpg",
        }
        assert archive.read("bookshelf.json").decode("utf-8").startswith("[")

    other = "13800000002"
    assert client.get(f"/api/v1/backups/{backup['id']}", headers=auth(other)).status_code == 404
    assert client.get("/api/v1/backups/latest", headers=auth(other)).status_code == 404
    assert client.get(f"/api/v1/backups/{backup['id']}/download", headers=auth(other)).status_code == 404


def test_duplicate_upload_and_keep_latest(client):
    phone = "13800000003"
    content = make_backup_zip()
    first = upload(client, phone, content).json()["backup"]
    second = upload(client, phone, content).json()
    assert second["created"] is False
    assert second["backup"]["id"] == first["id"]

    changed = upload(client, phone, make_backup_zip(extra=True)).json()["backup"]
    listing = client.get("/api/v1/backups", headers=auth(phone)).json()
    assert listing["total"] == 1
    assert listing["items"][0]["id"] == changed["id"]


def test_delete_backup(client):
    phone = "13800000004"
    backup_id = upload(client, phone, make_backup_zip()).json()["backup"]["id"]
    response = client.delete(f"/api/v1/backups/{backup_id}", headers=auth(phone))
    assert response.status_code == 200
    assert client.get(f"/api/v1/backups/{backup_id}", headers=auth(phone)).status_code == 404


def test_phone_header_is_required_and_validated(client):
    assert client.get("/api/v1/backups").status_code == 400
    assert (
        client.get("/api/v1/backups", headers={"X-User-Phone": "abc"}).status_code == 400
    )


def test_llm_returns_503_when_not_configured(client):
    response = client.post(
        "/api/v1/llm/polish",
        headers=auth("13800000005"),
        json={"text": "测试文本"},
    )
    assert response.status_code == 503


def test_phone_is_direct_user_credential(client):
    phone = "+8613800000006"
    me = client.get("/api/v1/users/me", headers=auth(phone))
    assert me.status_code == 200
    assert me.json()["phone"] == phone
