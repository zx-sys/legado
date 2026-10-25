# -*- coding: utf-8 -*-

"""
@Remark: Legado 手机号用户、备份、书架、书源、TTS 引擎模型
"""
from django.db import models

from utils.models import BaseModel, table_prefix


class LegadoUser(BaseModel):
    phone = models.CharField(max_length=32, unique=True, db_index=True, verbose_name="手机号")
    nickname = models.CharField(max_length=64, blank=True, default="", verbose_name="昵称")
    remark = models.CharField(max_length=255, blank=True, default="", verbose_name="备注")

    class Meta:
        db_table = table_prefix + "legado_user"
        verbose_name = "Legado 手机用户"
        verbose_name_plural = verbose_name
        ordering = ("-create_time",)

    def __str__(self):
        return self.phone


class Backup(BaseModel):
    TRIGGER_CHOICES = (("auto", "自动"), ("manual", "手动"))
    STATUS_CHOICES = (("ready", "可用"), ("failed", "失败"))

    user = models.ForeignKey(
        LegadoUser, on_delete=models.CASCADE, related_name="backups", verbose_name="用户"
    )
    device_name = models.CharField(max_length=128, default="default", verbose_name="设备")
    file_name = models.CharField(max_length=255, verbose_name="备份文件名")
    trigger = models.CharField(
        max_length=16, choices=TRIGGER_CHOICES, default="manual", verbose_name="触发方式"
    )
    status = models.CharField(
        max_length=16, choices=STATUS_CHOICES, default="ready", verbose_name="状态"
    )
    sha256 = models.CharField(max_length=64, db_index=True, verbose_name="SHA256")
    size_bytes = models.BigIntegerField(default=0, verbose_name="文件大小")
    item_count = models.IntegerField(default=0, verbose_name="文件数量")
    error = models.TextField(blank=True, default="", verbose_name="错误信息")

    class Meta:
        db_table = table_prefix + "legado_backup"
        verbose_name = "Legado 备份"
        verbose_name_plural = verbose_name
        ordering = ("-create_time",)
        indexes = [
            models.Index(fields=["user", "device_name", "create_time"]),
            models.Index(fields=["user", "sha256"]),
        ]

    def __str__(self):
        return f"{self.user.phone} - {self.file_name}"


class BackupItem(models.Model):
    backup = models.ForeignKey(
        Backup, on_delete=models.CASCADE, related_name="items", verbose_name="备份"
    )
    path = models.CharField(max_length=512, verbose_name="文件路径")
    content_type = models.CharField(max_length=128, default="application/octet-stream")
    storage = models.CharField(max_length=16, default="db", verbose_name="存储方式")
    content = models.TextField(null=True, blank=True, verbose_name="文本内容")
    object_key = models.CharField(max_length=512, null=True, blank=True, verbose_name="OSS Key")
    local_path = models.CharField(max_length=512, null=True, blank=True, verbose_name="本地路径")
    size_bytes = models.BigIntegerField(default=0)
    sha256 = models.CharField(max_length=64, blank=True, default="")

    class Meta:
        db_table = table_prefix + "legado_backup_item"
        verbose_name = "Legado 备份文件"
        verbose_name_plural = verbose_name
        unique_together = (("backup", "path"),)
        ordering = ("id",)


class BackupRecord(models.Model):
    backup = models.ForeignKey(
        Backup, on_delete=models.CASCADE, related_name="records", verbose_name="备份"
    )
    user = models.ForeignKey(
        LegadoUser, on_delete=models.CASCADE, related_name="backup_records", verbose_name="用户"
    )
    dataset = models.CharField(max_length=64, verbose_name="数据集")
    external_id = models.CharField(max_length=512, null=True, blank=True, verbose_name="外部ID")
    payload = models.JSONField(null=True, blank=True, verbose_name="原始记录")

    class Meta:
        db_table = table_prefix + "legado_backup_record"
        verbose_name = "Legado 备份记录"
        verbose_name_plural = verbose_name
        indexes = [models.Index(fields=["backup", "dataset"])]
        ordering = ("id",)


class LegadoBook(BaseModel):
    user = models.ForeignKey(
        LegadoUser, on_delete=models.CASCADE, related_name="books", verbose_name="用户"
    )
    last_backup = models.ForeignKey(
        Backup, on_delete=models.SET_NULL, null=True, blank=True,
        related_name="books", verbose_name="最后备份",
    )
    name = models.CharField(max_length=255, verbose_name="书名")
    author = models.CharField(max_length=255, blank=True, default="", verbose_name="作者")
    book_url = models.CharField(max_length=700, blank=True, default="", verbose_name="书籍URL")
    toc_url = models.CharField(max_length=700, blank=True, default="", verbose_name="目录URL")
    origin = models.CharField(max_length=1024, blank=True, default="", verbose_name="书源")
    origin_name = models.CharField(max_length=255, blank=True, default="", verbose_name="书源名称")
    kind = models.CharField(max_length=512, blank=True, default="", verbose_name="分类")
    custom_tag = models.CharField(max_length=512, blank=True, default="", verbose_name="自定义分类")
    group = models.CharField(max_length=255, blank=True, default="", verbose_name="分组")
    cover_url = models.CharField(max_length=1024, blank=True, default="", verbose_name="封面")
    custom_cover_url = models.CharField(
        max_length=1024, blank=True, default="", verbose_name="自定义封面"
    )
    intro = models.TextField(blank=True, default="", verbose_name="简介")
    custom_intro = models.TextField(blank=True, default="", verbose_name="自定义简介")
    charset = models.CharField(max_length=64, blank=True, default="", verbose_name="字符集")
    type = models.IntegerField(default=0, verbose_name="书籍类型")
    word_count = models.CharField(max_length=64, blank=True, default="", verbose_name="字数")
    variable = models.TextField(blank=True, default="", verbose_name="书籍变量")
    latest_chapter_title = models.CharField(
        max_length=512, blank=True, default="", verbose_name="最新章节"
    )
    latest_chapter_time = models.BigIntegerField(default=0, verbose_name="最新章节时间")
    dur_chapter_index = models.IntegerField(default=0, verbose_name="当前章节")
    dur_chapter_pos = models.IntegerField(default=0, verbose_name="章节位置")
    dur_chapter_title = models.CharField(
        max_length=512, blank=True, default="", verbose_name="当前章节名"
    )
    dur_chapter_time = models.BigIntegerField(default=0, verbose_name="阅读时间戳")
    total_chapter_num = models.IntegerField(default=0, verbose_name="章节总数")
    read_time = models.BigIntegerField(default=0, verbose_name="累计阅读时长")
    last_read_time = models.BigIntegerField(default=0, verbose_name="最后阅读时间")
    last_chapter_index = models.IntegerField(default=-1, verbose_name="最后阅读章节")
    last_chapter_pos = models.IntegerField(default=0, verbose_name="最后阅读章节位置")
    last_chapter_title = models.CharField(
        max_length=512, blank=True, default="", verbose_name="最后阅读章节名"
    )
    sync_updated_at = models.BigIntegerField(default=0, db_index=True, verbose_name="元数据更新时间")
    removed = models.BooleanField(default=False, db_index=True, verbose_name="已从书架删除")
    removed_at = models.BigIntegerField(default=0, verbose_name="删除时间")

    class Meta:
        db_table = table_prefix + "legado_book"
        verbose_name = "Legado 书架书籍"
        verbose_name_plural = verbose_name
        ordering = ("-dur_chapter_time", "-update_time")
        unique_together = (("user", "name", "author"),)
        indexes = [models.Index(fields=["user", "book_url"])]

    def __str__(self):
        return self.name


class LegadoBookGroup(BaseModel):
    user = models.ForeignKey(
        LegadoUser, on_delete=models.CASCADE, related_name="book_groups", verbose_name="用户"
    )
    name = models.CharField(max_length=255, verbose_name="分组名称")
    cover = models.CharField(max_length=1024, blank=True, default="", verbose_name="封面")
    order = models.IntegerField(default=0, verbose_name="排序")
    book_sort = models.IntegerField(default=-1, verbose_name="书籍排序")
    enable_refresh = models.BooleanField(default=True, verbose_name="允许刷新")
    show = models.BooleanField(default=True, verbose_name="显示")
    only_update_read = models.BooleanField(default=False, verbose_name="只更新已读")
    sync_updated_at = models.BigIntegerField(default=0, db_index=True, verbose_name="更新时间")
    removed = models.BooleanField(default=False, db_index=True, verbose_name="已删除")
    removed_at = models.BigIntegerField(default=0, verbose_name="删除时间")

    class Meta:
        db_table = table_prefix + "legado_book_group"
        verbose_name = "Legado 书架分组"
        verbose_name_plural = verbose_name
        unique_together = (("user", "name"),)
        ordering = ("order", "create_time")

    def __str__(self):
        return self.name


class UserConfig(BaseModel):
    CONFIG_TYPE_CHOICES = (
        ("read_config", "阅读排版"),
        ("share_read_config", "分享排版"),
        ("theme_config", "主题配置"),
        ("app_config", "应用设置"),
        ("app_settings", "应用设置项"),
        ("video_config", "视频设置"),
        ("book_source_config", "书源选择"),
    )

    user = models.ForeignKey(
        LegadoUser, on_delete=models.CASCADE, related_name="configs", verbose_name="用户"
    )
    last_backup = models.ForeignKey(
        Backup, on_delete=models.SET_NULL, null=True, blank=True,
        related_name="configs", verbose_name="最后备份",
    )
    config_type = models.CharField(
        max_length=32, choices=CONFIG_TYPE_CHOICES, verbose_name="配置类型"
    )
    source_file = models.CharField(max_length=128, verbose_name="来源文件")
    content = models.TextField(blank=True, default="", verbose_name="配置内容")
    raw_json = models.JSONField(null=True, blank=True, verbose_name="结构化配置")

    class Meta:
        db_table = table_prefix + "legado_user_config"
        verbose_name = "Legado 用户配置"
        verbose_name_plural = verbose_name
        unique_together = (("user", "config_type"),)
        ordering = ("config_type",)

    def __str__(self):
        return f"{self.user.phone} - {self.get_config_type_display()}"


class LegadoBookmark(BaseModel):
    book = models.ForeignKey(
        LegadoBook, on_delete=models.CASCADE, related_name="bookmarks", verbose_name="书籍"
    )
    time = models.BigIntegerField(verbose_name="书签时间")
    chapter_index = models.IntegerField(default=0, verbose_name="章节索引")
    chapter_pos = models.IntegerField(default=0, verbose_name="章节位置")
    chapter_name = models.CharField(max_length=512, blank=True, default="", verbose_name="章节名")
    book_text = models.TextField(blank=True, default="", verbose_name="书签文本")
    content = models.TextField(blank=True, default="", verbose_name="备注")

    class Meta:
        db_table = table_prefix + "legado_bookmark"
        verbose_name = "Legado 书签"
        verbose_name_plural = verbose_name
        unique_together = (("book", "time"),)
        ordering = ("-time",)


class LegadoHighlight(BaseModel):
    book = models.ForeignKey(
        LegadoBook, on_delete=models.CASCADE, related_name="highlights", verbose_name="书籍"
    )
    time = models.BigIntegerField(verbose_name="标注时间")
    chapter_url = models.CharField(max_length=1024, blank=True, default="")
    book_url = models.CharField(max_length=1024, blank=True, default="")
    chapter_index = models.IntegerField(default=0, verbose_name="章节索引")
    chapter_pos = models.IntegerField(default=0, verbose_name="起点")
    chapter_pos_end = models.IntegerField(default=0, verbose_name="终点")
    layout_title_length = models.IntegerField(default=-1, verbose_name="标题长度")
    chapter_name = models.CharField(max_length=512, blank=True, default="", verbose_name="章节名")
    book_text = models.TextField(blank=True, default="", verbose_name="标注文本")
    style = models.TextField(blank=True, default="", verbose_name="样式")
    note = models.TextField(blank=True, default="", verbose_name="笔记")

    class Meta:
        db_table = table_prefix + "legado_highlight"
        verbose_name = "Legado 标注"
        verbose_name_plural = verbose_name
        unique_together = (("book", "time"),)
        ordering = ("-time",)


class BookSource(BaseModel):
    name = models.CharField(max_length=255, verbose_name="书源名称")
    source_url = models.CharField(
        max_length=700, unique=True, db_index=True, verbose_name="书源URL"
    )
    group = models.CharField(max_length=255, blank=True, default="", verbose_name="分组")
    source_type = models.IntegerField(default=0, verbose_name="类型")
    enabled = models.BooleanField(default=True, verbose_name="启用")
    weight = models.IntegerField(default=0, verbose_name="权重")
    search_url = models.TextField(blank=True, default="", verbose_name="搜索URL")
    comment = models.TextField(blank=True, default="", verbose_name="备注")
    check_status = models.CharField(max_length=32, blank=True, default="", verbose_name="校验状态")
    last_check_time = models.DateTimeField(null=True, blank=True, verbose_name="最后校验")
    raw_json = models.JSONField(default=dict, verbose_name="完整书源JSON")

    class Meta:
        db_table = table_prefix + "legado_book_source"
        verbose_name = "Legado 书源"
        verbose_name_plural = verbose_name
        ordering = ("-update_time",)
        indexes = [models.Index(fields=["enabled", "group"])]

    def __str__(self):
        return self.name


class FontPack(BaseModel):
    name = models.CharField(max_length=128, verbose_name="字体名称")
    code = models.CharField(max_length=64, unique=True, db_index=True, verbose_name="字体标识")
    languages = models.CharField(
        max_length=64, blank=True, default="zh", verbose_name="字体语言", help_text="例如 zh,en"
    )
    version = models.CharField(max_length=64, blank=True, default="", verbose_name="版本")
    license = models.CharField(max_length=255, blank=True, default="", verbose_name="许可证")
    source_url = models.CharField(max_length=1024, blank=True, default="", verbose_name="来源地址")
    description = models.TextField(blank=True, default="", verbose_name="说明")
    package_url = models.CharField(max_length=1024, blank=True, default="", verbose_name="下载地址")
    package_key = models.CharField(max_length=512, blank=True, default="", verbose_name="OSS Key")
    package_file_name = models.CharField(
        max_length=255, blank=True, default="", verbose_name="包文件名"
    )
    package_size = models.BigIntegerField(default=0, verbose_name="包大小")
    sha256 = models.CharField(max_length=64, blank=True, default="", verbose_name="文件SHA256")
    enabled = models.BooleanField(default=True, verbose_name="启用")
    sort = models.IntegerField(default=1, verbose_name="排序")
    download_count = models.IntegerField(default=0, verbose_name="下载次数")

    class Meta:
        db_table = table_prefix + "legado_font_pack"
        verbose_name = "Legado 字体包"
        verbose_name_plural = verbose_name
        ordering = ("sort", "-create_time")

    def __str__(self):
        return self.name


class TtsEngine(BaseModel):
    ENGINE_TYPE_CHOICES = (
        ("sherpa_onnx", "sherpa-onnx"),
        ("system", "系统 TTS"),
        ("other", "其他"),
    )
    name = models.CharField(max_length=128, verbose_name="引擎名称")
    code = models.CharField(max_length=64, unique=True, db_index=True, verbose_name="引擎标识")
    engine_type = models.CharField(
        max_length=32, choices=ENGINE_TYPE_CHOICES, default="sherpa_onnx", verbose_name="类型"
    )
    languages = models.CharField(
        max_length=128, blank=True, default="", verbose_name="支持语言", help_text="例如 zh,en"
    )
    speaker_count = models.IntegerField(default=1, verbose_name="音色数量")
    version = models.CharField(max_length=64, blank=True, default="", verbose_name="版本")
    license = models.CharField(max_length=128, blank=True, default="", verbose_name="许可证")
    source_url = models.CharField(max_length=1024, blank=True, default="", verbose_name="来源地址")
    description = models.TextField(blank=True, default="", verbose_name="说明")
    icon_url = models.CharField(max_length=1024, blank=True, default="", verbose_name="图标")
    package_url = models.CharField(max_length=1024, blank=True, default="", verbose_name="下载地址")
    package_key = models.CharField(max_length=512, blank=True, default="", verbose_name="OSS Key")
    package_file_name = models.CharField(
        max_length=255, blank=True, default="", verbose_name="包文件名"
    )
    package_size = models.BigIntegerField(default=0, verbose_name="包大小")
    config = models.JSONField(default=dict, blank=True, verbose_name="扩展配置")
    enabled = models.BooleanField(default=True, verbose_name="启用")
    is_default = models.BooleanField(default=False, verbose_name="默认引擎")
    sort = models.IntegerField(default=1, verbose_name="排序")
    download_count = models.IntegerField(default=0, verbose_name="下载次数")

    class Meta:
        db_table = table_prefix + "legado_tts_engine"
        verbose_name = "Legado TTS 引擎"
        verbose_name_plural = verbose_name
        ordering = ("sort", "-create_time")

    def __str__(self):
        return self.name
