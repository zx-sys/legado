from django.test import TestCase
from rest_framework.test import APIClient

from apps.polish.models import PolishJob


class PolishApiTest(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.headers = {"HTTP_X_USER_PHONE": "13800000003"}

    def test_upload_and_pull(self):
        body = {
            "bookUrl": "https://e.com/1",
            "bookName": "书",
            "bookAuthor": "作者",
            "sourceUrl": "src",
            "priority": 0,
            "chapters": [
                {
                    "index": 1,
                    "url": "https://e.com/1/1",
                    "title": "第1章",
                    "hash": "a" * 32,
                    "text": "第一段。\n第二段。",
                }
            ],
        }
        resp = self.client.post(
            "/api/legado/app/polish/jobs", body, format="json", **self.headers
        )
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json()["accepted"], 1)

        job = PolishJob.objects.get()
        self.assertEqual(job.priority, 0)
        job.status = "done"
        job.verdict = "ok"
        job.patch_json = {
            "verdict": "ok",
            "edits": [{"p": 0, "start": 0, "end": 3, "from": "第一段", "replace": "第一段"}],
        }
        job.source_hash = "a" * 32
        job.save()

        resp = self.client.get(
            "/api/legado/app/polish/patches?bookUrl=https%3A%2F%2Fe.com%2F1&since=0",
            **self.headers,
        )
        self.assertEqual(resp.status_code, 200)
        patches = resp.json()["patches"]
        self.assertEqual(len(patches), 1)
        self.assertEqual(patches[0]["sourceHash"], "a" * 32)
        self.assertEqual(patches[0]["status"], "done")

    def test_missing_phone(self):
        resp = self.client.post("/api/legado/app/polish/jobs", {}, format="json")
        self.assertEqual(resp.status_code, 400)

    def test_status(self):
        resp = self.client.get(
            "/api/legado/app/polish/status?bookUrl=https%3A%2F%2Fe.com%2F1",
            **self.headers,
        )
        self.assertEqual(resp.status_code, 200)
        self.assertIn("queued", resp.json())
