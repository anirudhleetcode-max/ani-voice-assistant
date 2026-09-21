"""Ani's AI backend.

One endpoint, one job: take a sentence and return a sentence.

It exists so that no API key ever ships inside the Android app, and so that Ani's persona
is defined somewhere a user cannot accidentally edit. It is intentionally incapable of
anything else — it cannot see the user's contacts, messages or notifications, it is never
sent them, and it has no way to make the phone do anything.
"""

from __future__ import annotations

import logging

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from .config import Settings, get_settings
from .prompts import build_system_prompt
from .providers import ProviderError, ReplyRequest, Turn, build_provider
from .ratelimit import RateLimiter

logger = logging.getLogger("ani.backend")

MAX_TEXT_LENGTH = 2000
MAX_TURNS = 12

app = FastAPI(title="Ani backend", version="1.0.0", docs_url=None, redoc_url=None)


class TurnBody(BaseModel):
    role: str = Field(pattern="^(user|assistant)$")
    text: str = Field(max_length=MAX_TEXT_LENGTH)


class ReplyBody(BaseModel):
    text: str = Field(min_length=1, max_length=MAX_TEXT_LENGTH)
    language: str = "MIXED"
    persona: str = "FRIENDLY"
    turns: list[TurnBody] = Field(default_factory=list, max_length=MAX_TURNS)
    context: str | None = Field(default=None, max_length=500)


class ReplyResponse(BaseModel):
    reply: str | None = None
    error: str | None = None


def _limiter(settings: Settings = Depends(get_settings)) -> RateLimiter:
    # One limiter per process, created on first use.
    if not hasattr(app.state, "limiter"):
        app.state.limiter = RateLimiter(settings.rate_limit_per_minute)
    return app.state.limiter


@app.get("/health")
async def health(settings: Settings = Depends(get_settings)) -> dict:
    """Reports which provider is actually active, including the echo fallback."""
    provider = build_provider(settings)
    return {
        "status": "ok",
        "provider": provider.name,
        "configured_provider": settings.provider,
        "credentials_present": settings.has_credentials,
    }


@app.post("/v1/reply", response_model=ReplyResponse)
async def reply(
    body: ReplyBody,
    request: Request,
    settings: Settings = Depends(get_settings),
    limiter: RateLimiter = Depends(_limiter),
    x_ani_install: str | None = Header(default=None),
) -> ReplyResponse:
    # The install id is an opaque random value the app generates; it is not an account and
    # is not tied to the user's identity. Requests without one share a bucket.
    key = x_ani_install or (request.client.host if request.client else "anonymous")
    if not limiter.allow(key):
        raise HTTPException(status_code=429, detail="Too many requests")

    provider = build_provider(settings)
    system_prompt = build_system_prompt(body.language, body.persona)

    provider_request = ReplyRequest(
        text=body.text,
        language=body.language,
        persona=body.persona,
        turns=[Turn(role=turn.role, text=turn.text) for turn in body.turns],
        context=body.context,
    )

    try:
        text = await provider.reply(provider_request, system_prompt)
    except ProviderError as error:
        # Log the shape of the failure, never the user's words.
        logger.warning("provider failed: %s", error)
        return ReplyResponse(error="The assistant service is having trouble.")

    return ReplyResponse(reply=text)


@app.exception_handler(HTTPException)
async def http_exception_handler(_: Request, exc: HTTPException) -> JSONResponse:
    """Errors come back in the same shape as successes, so the client parses one thing."""
    return JSONResponse(status_code=exc.status_code, content={"error": exc.detail})
