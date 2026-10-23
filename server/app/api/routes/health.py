from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.core.cache import cache
from app.core.config import settings
from app.core.database import get_db
from app.services.storage import storage

router = APIRouter(tags=["health"])


@router.get("/health")
def health(db: Session = Depends(get_db)):
    try:
        db.execute(text("SELECT 1"))
        database_ok = True
    except Exception:
        database_ok = False
    body = {
        "status": "ok" if database_ok else "degraded",
        "database": database_ok,
        "redis": cache.ping(),
        "storage": storage.mode,
        "deepseek_configured": bool(settings.deepseek_api_key),
    }
    return JSONResponse(status_code=200 if database_ok else 503, content=body)
