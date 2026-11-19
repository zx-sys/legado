from django.test import TestCase
from rest_framework.test import APIClient

from apps.polish.models import PolishSourceProfile


class ProfileApiTest(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.headers = {"HTTP_X_USER_PHONE": "13800000006"}

    def test_verified_profile(self):
        PolishSourceProfile.objects.create(
            source_url="https://src",
            status="verified",
            version=3,
            profile_json={"char_map": {"乾": {"to": "干", "exceptions": ["乾坤"]}}},
            confidence=0.95,
        )
        resp = self.client.get(
            "/api/legado/app/polish/source-profile?sourceUrl=https%3A%2F%2Fsrc",
            **self.headers,
        )
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json()["version"], 3)
        self.assertEqual(resp.json()["profile"]["char_map"]["乾"]["to"], "干")

    def test_draft_not_served(self):
        PolishSourceProfile.objects.create(
            source_url="https://src", status="draft", profile_json={}
        )
        resp = self.client.get(
            "/api/legado/app/polish/source-profile?sourceUrl=https%3A%2F%2Fsrc",
            **self.headers,
        )
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json(), {})
