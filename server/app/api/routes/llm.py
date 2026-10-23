from fastapi import APIRouter, Depends, HTTPException

from app.api.deps import get_current_user
from app.models import User
from app.schemas.backup import LLMPolishIn, LLMPolishOut
from app.services.deepseek import LLMError, LLMNotConfigured, deepseek

router = APIRouter(prefix="/llm", tags=["llm"])


@router.post("/polish", response_model=LLMPolishOut)
async def polish_text(
    body: LLMPolishIn,
    _user: User = Depends(get_current_user),
) -> LLMPolishOut:
    try:
        result = await deepseek.polish(body.text, body.instruction)
    except LLMNotConfigured as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except LLMError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    return LLMPolishOut(model=result.model, result=result.content)
