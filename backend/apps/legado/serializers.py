# -*- coding: utf-8 -*-
from rest_framework import serializers

from apps.legado.models import (
    Backup,
    BackupItem,
    BackupRecord,
    BookSource,
    LegadoBook,
    LegadoBookmark,
    LegadoHighlight,
    LegadoUser,
    TtsEngine,
    UserConfig,
)
from utils.serializers import CustomModelSerializer


class LegadoUserSerializer(CustomModelSerializer):
    backup_count = serializers.SerializerMethodField()
    book_count = serializers.SerializerMethodField()

    class Meta:
        model = LegadoUser
        fields = "__all__"
        read_only_fields = ("create_time", "update_time", "is_delete")

    def get_backup_count(self, obj):
        return obj.backups.count()

    def get_book_count(self, obj):
        return obj.books.count()


class BackupSerializer(CustomModelSerializer):
    user_phone = serializers.CharField(source="user.phone", read_only=True)
    user_nickname = serializers.CharField(source="user.nickname", read_only=True)

    class Meta:
        model = Backup
        fields = "__all__"
        read_only_fields = ("create_time", "update_time", "is_delete", "sha256", "size_bytes")


class BackupItemSerializer(CustomModelSerializer):
    class Meta:
        model = BackupItem
        fields = ("id", "path", "content_type", "storage", "size_bytes", "sha256")


class BackupRecordSerializer(CustomModelSerializer):
    class Meta:
        model = BackupRecord
        fields = "__all__"


class LegadoBookmarkSerializer(CustomModelSerializer):
    class Meta:
        model = LegadoBookmark
        fields = "__all__"
        read_only_fields = ("create_time", "update_time", "is_delete", "book")


class LegadoHighlightSerializer(CustomModelSerializer):
    class Meta:
        model = LegadoHighlight
        fields = "__all__"
        read_only_fields = ("create_time", "update_time", "is_delete", "book")


class LegadoBookSerializer(CustomModelSerializer):
    user_phone = serializers.CharField(source="user.phone", read_only=True)
    bookmark_count = serializers.SerializerMethodField()
    highlight_count = serializers.SerializerMethodField()

    class Meta:
        model = LegadoBook
        fields = "__all__"
        read_only_fields = ("create_time", "update_time", "is_delete", "last_backup")


class UserConfigSerializer(CustomModelSerializer):
    user_phone = serializers.CharField(source="user.phone", read_only=True)

    class Meta:
        model = UserConfig
        fields = "__all__"
        read_only_fields = ("create_time", "update_time", "is_delete", "last_backup", "source_file")


class BookSourceSerializer(CustomModelSerializer):
    class Meta:
        model = BookSource
        fields = "__all__"
        read_only_fields = ("create_time", "update_time", "is_delete")


class TtsEngineSerializer(CustomModelSerializer):
    class Meta:
        model = TtsEngine
        fields = "__all__"
        read_only_fields = (
            "create_time",
            "update_time",
            "is_delete",
            "package_key",
            "package_file_name",
            "package_size",
            "download_count",
        )
