import hashlib
from datetime import timedelta

from django.conf import settings
from django.utils import timezone

from apps.polish.models import PolishAlignSample


def maybe_store_sample(
    source_url, book_key, book_url, chapter_url, chapter_index, title, text
) -> bool:
    if not source_url or not text:
        return False
    chapter_key = hashlib.md5(chapter_url.encode("utf-8")).hexdigest()
    limit = int(getattr(settings, "POLISH_PROFILE_SAMPLE_LIMIT", 20))
    current = PolishAlignSample.objects.filter(source_url=source_url).count()
    if current >= limit:
        return False
    if PolishAlignSample.objects.filter(
        source_url=source_url, chapter_key=chapter_key
    ).exists():
        return False
    has_counterpart = (
        PolishAlignSample.objects.filter(
            book_key=book_key, chapter_index=chapter_index
        )
        .exclude(source_url=source_url)
        .exists()
    )
    if not has_counterpart and current >= max(1, limit // 2):
        return False
    max_chars = int(getattr(settings, "POLISH_PROFILE_SAMPLE_CHARS", 4000))
    ttl_days = int(getattr(settings, "POLISH_PROFILE_SAMPLE_TTL_DAYS", 30))
    PolishAlignSample.objects.create(
        source_url=source_url,
        book_key=book_key,
        book_url=book_url,
        chapter_url=chapter_url,
        chapter_key=chapter_key,
        chapter_index=chapter_index,
        chapter_title=title or "",
        text=text[:max_chars],
        content_hash=hashlib.md5(text.encode("utf-8")).hexdigest(),
        expire_time=timezone.now() + timedelta(days=ttl_days),
    )
    return True


def purge_expired_samples() -> int:
    deleted, _ = PolishAlignSample.objects.filter(
        expire_time__lt=timezone.now()
    ).delete()
    return deleted
