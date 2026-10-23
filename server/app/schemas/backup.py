from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class UserOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    phone: str
    nickname: str | None
    created_at: datetime


class UserUpdate(BaseModel):
    nickname: str | None = Field(default=None, max_length=64)


class BackupItemOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    path: str
    content_type: str
    storage: str
    size_bytes: int
    sha256: str


class BackupOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    device_name: str
    file_name: str
    trigger: str
    status: str
    sha256: str
    size_bytes: int
    item_count: int
    created_at: datetime


class BackupDetail(BackupOut):
    items: list[BackupItemOut]
    datasets: dict[str, int] = Field(default_factory=dict)


class BackupUploadOut(BaseModel):
    created: bool
    backup: BackupOut


class BackupListOut(BaseModel):
    total: int
    items: list[BackupOut]


class BackupRecordOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    dataset: str
    external_id: str | None
    payload: Any


class LLMPolishIn(BaseModel):
    text: str = Field(min_length=1, max_length=200_000)
    instruction: str | None = Field(default=None, max_length=2_000)
    temperature: float = Field(default=0.7, ge=0.0, le=2.0)


class LLMPolishOut(BaseModel):
    model: str
    result: str
