from datetime import datetime
from zoneinfo import ZoneInfo

from django.conf import settings
from django.db.models import Count
from rest_framework.response import Response

from apps.legado.views_app import NoAuthAPIView, get_user
from apps.polish.models import PolishBookState, PolishJob, PolishSourceProfile
from apps.polish.services import budget_left_micros, enqueue_chapters


class PolishJobsView(NoAuthAPIView):
    def post(self, request):
        user, error = get_user(request)
        if error:
            return error
        data = request.data or {}
        chapters = data.get("chapters") or []
        if not isinstance(chapters, list) or len(chapters) > 20:
            return Response({"detail": "chapters 最多 20 章"}, status=400)
        total_chars = 0
        for ch in chapters:
            if (
                not isinstance(ch, dict)
                or not ch.get("url")
                or not ch.get("hash")
                or "index" not in ch
            ):
                return Response({"detail": "chapters 字段不完整"}, status=400)
            total_chars += len(ch.get("text") or "")
        if total_chars > 200_000:
            return Response({"detail": "单批正文不能超过 200000 字"}, status=400)
        book_url = str(data.get("bookUrl") or "").strip()
        if not book_url:
            return Response({"detail": "bookUrl 必填"}, status=400)
        raw_priority = data.get("priority", 100)
        try:
            priority = 100 if raw_priority is None else int(raw_priority)
        except (TypeError, ValueError):
            return Response({"detail": "priority 非法"}, status=400)
        result = enqueue_chapters(
            user,
            book_url,
            str(data.get("bookName") or ""),
            str(data.get("sourceUrl") or ""),
            chapters,
            priority=priority,
            profile_version=int(data.get("profileVersion") or 0),
            book_author=str(data.get("bookAuthor") or ""),
        )
        result["budgetLeftMicros"] = budget_left_micros(user)
        return Response(result)


class PolishPatchesView(NoAuthAPIView):
    def get(self, request):
        user, error = get_user(request)
        if error:
            return error
        book_url = request.query_params.get("bookUrl") or ""
        if not book_url:
            return Response({"detail": "bookUrl 必填"}, status=400)
        since = request.query_params.get("since") or "0"
        try:
            since_ms = int(since)
        except ValueError:
            since_ms = 0
        try:
            limit = min(int(request.query_params.get("limit") or 50), 200)
        except (TypeError, ValueError):
            limit = 50

        qs = (
            PolishJob.objects.filter(user=user, book_url=book_url)
            .exclude(status="queued")
            .exclude(status="processing")
        )
        if since_ms:
            since_dt = datetime.fromtimestamp(
                since_ms / 1000, tz=ZoneInfo(settings.TIME_ZONE)
            ).replace(tzinfo=None)
            qs = qs.filter(update_time__gt=since_dt)
        jobs = qs.order_by("chapter_index")[:limit]
        patches = [
            {
                "chapterIndex": j.chapter_index,
                "chapterUrl": j.chapter_url,
                "sourceHash": j.source_hash,
                "status": j.status,
                "verdict": j.verdict,
                "boundary": j.boundary_paragraph,
                "reason": j.verdict_reason,
                "patch": j.patch_json,
                "costMicros": j.cost_micros,
                "updatedAt": int(j.update_time.timestamp() * 1000) if j.update_time else 0,
            }
            for j in jobs
        ]
        return Response(
            {
                "patches": patches,
                "serverTime": int(datetime.now().timestamp() * 1000),
                "budgetLeftMicros": budget_left_micros(user),
            }
        )


class PolishSourceProfileView(NoAuthAPIView):
    def get(self, request):
        user, error = get_user(request)
        if error:
            return error
        source_url = request.query_params.get("sourceUrl") or ""
        obj = PolishSourceProfile.objects.filter(
            source_url=source_url, status="verified"
        ).first()
        if not obj:
            return Response({})
        return Response(
            {
                "sourceUrl": obj.source_url,
                "version": obj.version,
                "confidence": obj.confidence,
                "profile": obj.profile_json,
                "updatedAt": int(obj.update_time.timestamp() * 1000)
                if obj.update_time
                else 0,
            }
        )


class PolishStatusView(NoAuthAPIView):
    def get(self, request):
        user, error = get_user(request)
        if error:
            return error
        book_url = request.query_params.get("bookUrl") or ""
        counts = dict(
            PolishJob.objects.filter(user=user, book_url=book_url)
            .values_list("status")
            .annotate(n=Count("id"))
            .values_list("status", "n")
        )
        state = PolishBookState.objects.filter(user=user, book_url=book_url).first()
        return Response(
            {
                "queued": counts.get("queued", 0),
                "processing": counts.get("processing", 0),
                "done": counts.get("done", 0),
                "contaminated": counts.get("contaminated", 0),
                "suspicious": counts.get("suspicious", 0),
                "failed": counts.get("failed", 0),
                "bookStatus": state.status if state else "normal",
                "budgetLeftMicros": budget_left_micros(user),
            }
        )
