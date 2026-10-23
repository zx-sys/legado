import re

from fastapi import Depends, Header, HTTPException
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.models import User

PHONE_PATTERN = re.compile(r"^\+?\d{6,20}$")


def normalize_phone(raw: str) -> str:
    phone = re.sub(r"[\s\-()]", "", raw.strip())
    if not PHONE_PATTERN.fullmatch(phone):
        raise HTTPException(status_code=400, detail="invalid phone number")
    return phone


def get_current_user(
    x_user_phone: str | None = Header(default=None, alias="X-User-Phone"),
    db: Session = Depends(get_db),
) -> User:
    if not x_user_phone:
        raise HTTPException(status_code=400, detail="X-User-Phone header is required")
    phone = normalize_phone(x_user_phone)
    user = db.query(User).filter(User.phone == phone).first()
    if user is not None:
        return user
    user = User(phone=phone)
    db.add(user)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        user = db.query(User).filter(User.phone == phone).one()
        return user
    db.refresh(user)
    return user
