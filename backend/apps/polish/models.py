from django.db import models

from apps.legado.models import LegadoUser
from utils.models import BaseModel


class PolishJob(BaseModel):
    STATUS_CHOICES = (
        ("queued", "排队中"),
        ("processing", "处理中"),
        ("done", "已生成补丁"),
        ("contaminated", "付费残缺/拼接"),
        ("suspicious", "疑似拼接"),
        ("rejected", "校验未通过"),
        ("failed", "失败"),
        ("skipped", "跳过"),
    )

    user = models.ForeignKey(
        LegadoUser, on_delete=models.CASCADE, related_name="polish_jobs"
    )
    book_url = models.CharField(max_length=700, db_index=True)
    book_name = models.CharField(max_length=255, blank=True, default="")
    book_author = models.CharField(max_length=255, blank=True, default="")
    source_url = models.CharField(max_length=700, blank=True, default="")
    chapter_url = models.CharField(max_length=700, db_index=True)
    chapter_index = models.IntegerField()
    chapter_title = models.CharField(max_length=512, blank=True, default="")
    source_hash = models.CharField(max_length=32, db_index=True)
    profile_version = models.IntegerField(default=0)
    raw_text = models.TextField(null=True, blank=True)
    status = models.CharField(
        max_length=16, choices=STATUS_CHOICES, default="queued", db_index=True
    )
    priority = models.SmallIntegerField(default=100)
    attempts = models.SmallIntegerField(default=0)
    verdict = models.CharField(max_length=16, blank=True, default="")
    verdict_reason = models.CharField(max_length=512, blank=True, default="")
    boundary_paragraph = models.IntegerField(null=True, blank=True)
    patch_json = models.JSONField(null=True, blank=True)
    tokens_in = models.IntegerField(default=0)
    tokens_out = models.IntegerField(default=0)
    cost_micros = models.BigIntegerField(default=0)
    model = models.CharField(max_length=64, blank=True, default="")
    error = models.TextField(blank=True, default="")

    class Meta:
        db_table = "cc_polish_job"
        unique_together = ("user", "book_url", "chapter_index", "source_hash")
        indexes = [models.Index(fields=["status", "priority", "create_time"])]

    def __str__(self):
        return f"{self.book_name} #{self.chapter_index} [{self.status}]"


class PolishPatchCache(BaseModel):
    source_url = models.CharField(max_length=700, blank=True, default="")
    chapter_url = models.CharField(max_length=700, db_index=True)
    source_hash = models.CharField(max_length=32, db_index=True)
    profile_version = models.IntegerField(default=0)
    verdict = models.CharField(max_length=16, default="ok")
    verdict_reason = models.CharField(max_length=512, blank=True, default="")
    boundary_paragraph = models.IntegerField(null=True, blank=True)
    patch_json = models.JSONField(null=True, blank=True)
    model = models.CharField(max_length=64, blank=True, default="")

    class Meta:
        db_table = "cc_polish_patch_cache"
        unique_together = ("chapter_url", "source_hash")


class PolishQuota(BaseModel):
    user = models.ForeignKey(
        LegadoUser, on_delete=models.CASCADE, related_name="polish_quotas"
    )
    date = models.DateField()
    spent_micros = models.BigIntegerField(default=0)
    job_count = models.IntegerField(default=0)

    class Meta:
        db_table = "cc_polish_quota"
        unique_together = ("user", "date")


class PolishBookState(BaseModel):
    STATUS_CHOICES = (
        ("normal", "正常"),
        ("paid_truncated", "付费残缺"),
        ("paused", "暂停"),
    )

    user = models.ForeignKey(
        LegadoUser, on_delete=models.CASCADE, related_name="polish_book_states"
    )
    book_url = models.CharField(max_length=700, db_index=True)
    book_name = models.CharField(max_length=255, blank=True, default="")
    status = models.CharField(max_length=16, choices=STATUS_CHOICES, default="normal")
    contaminated_streak = models.IntegerField(default=0)
    last_reason = models.CharField(max_length=512, blank=True, default="")

    class Meta:
        db_table = "cc_polish_book_state"
        unique_together = ("user", "book_url")


class PolishSourceProfile(BaseModel):
    STATUS_CHOICES = (
        ("draft", "草稿"),
        ("verified", "已审核"),
        ("disabled", "已停用"),
    )

    source_url = models.CharField(max_length=700, unique=True)
    version = models.IntegerField(default=1)
    status = models.CharField(max_length=16, choices=STATUS_CHOICES, default="draft")
    profile_json = models.JSONField(default=dict)
    confidence = models.FloatField(default=0)
    sample_count = models.IntegerField(default=0)
    holdout_false_change_rate = models.FloatField(default=1.0)
    reviewer = models.CharField(max_length=64, blank=True, default="")
    review_note = models.CharField(max_length=512, blank=True, default="")

    class Meta:
        db_table = "cc_polish_source_profile"


class PolishAlignSample(BaseModel):
    source_url = models.CharField(max_length=700, db_index=True)
    book_key = models.CharField(max_length=128, db_index=True)
    book_url = models.CharField(max_length=700, db_index=True)
    chapter_url = models.CharField(max_length=1024)
    chapter_key = models.CharField(max_length=32, null=True, blank=True, db_index=True)
    chapter_index = models.IntegerField(default=0)
    chapter_title = models.CharField(max_length=512, blank=True, default="")
    text = models.TextField()
    content_hash = models.CharField(max_length=32, db_index=True)
    expire_time = models.DateTimeField(null=True, blank=True)

    class Meta:
        db_table = "cc_polish_align_sample"
        unique_together = ("source_url", "chapter_key")
        indexes = [models.Index(fields=["book_key", "chapter_index"])]
