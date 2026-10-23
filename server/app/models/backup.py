from datetime import datetime

from sqlalchemy import (
    JSON,
    BigInteger,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.dialects import mysql
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base


class Backup(Base):
    __tablename__ = "backups"
    __table_args__ = (
        Index("ix_backups_user_device_created", "user_id", "device_name", "created_at"),
        Index("ix_backups_user_sha", "user_id", "sha256"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    device_name: Mapped[str] = mapped_column(String(128), nullable=False, default="default")
    file_name: Mapped[str] = mapped_column(String(255), nullable=False)
    trigger: Mapped[str] = mapped_column(String(16), nullable=False, default="manual")
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="ready")
    sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    size_bytes: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    item_count: Mapped[int] = mapped_column(nullable=False, default=0)
    error: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, index=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)

    user: Mapped["User"] = relationship(back_populates="backups")  # noqa: F821
    items: Mapped[list["BackupItem"]] = relationship(
        back_populates="backup", cascade="all, delete-orphan", passive_deletes=True
    )
    records: Mapped[list["BackupRecord"]] = relationship(
        back_populates="backup", cascade="all, delete-orphan", passive_deletes=True
    )


class BackupItem(Base):
    __tablename__ = "backup_items"
    __table_args__ = (UniqueConstraint("backup_id", "path", name="uq_backup_item_path"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    backup_id: Mapped[int] = mapped_column(
        ForeignKey("backups.id", ondelete="CASCADE"), nullable=False, index=True
    )
    path: Mapped[str] = mapped_column(String(512), nullable=False)
    content_type: Mapped[str] = mapped_column(String(128), nullable=False)
    storage: Mapped[str] = mapped_column(String(16), nullable=False, default="db")
    content: Mapped[str | None] = mapped_column(
        Text().with_variant(mysql.LONGTEXT(), "mysql"), nullable=True
    )
    object_key: Mapped[str | None] = mapped_column(String(512), nullable=True)
    local_path: Mapped[str | None] = mapped_column(String(512), nullable=True)
    size_bytes: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    sha256: Mapped[str] = mapped_column(String(64), nullable=False)

    backup: Mapped[Backup] = relationship(back_populates="items")


class BackupRecord(Base):
    __tablename__ = "backup_records"
    __table_args__ = (Index("ix_backup_records_dataset", "backup_id", "dataset"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    backup_id: Mapped[int] = mapped_column(
        ForeignKey("backups.id", ondelete="CASCADE"), nullable=False, index=True
    )
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    dataset: Mapped[str] = mapped_column(String(64), nullable=False)
    external_id: Mapped[str | None] = mapped_column(String(512), nullable=True)
    payload: Mapped[dict | list | str | int | float | bool | None] = mapped_column(
        JSON, nullable=True
    )

    backup: Mapped[Backup] = relationship(back_populates="records")
