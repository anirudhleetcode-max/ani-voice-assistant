"""Provider selection."""

from __future__ import annotations

from ..config import Settings
from .anthropic import AnthropicProvider
from .base import EchoProvider, Provider, ProviderError, ReplyRequest, Turn
from .openai import OpenAIProvider

__all__ = [
    "Provider",
    "ProviderError",
    "ReplyRequest",
    "Turn",
    "build_provider",
]


def build_provider(settings: Settings) -> Provider:
    """Pick the configured provider, falling back to echo when no key is present.

    Falling back rather than failing is deliberate: a developer running the stack for the
    first time should get a working app that says "I can't answer that right now", not a
    crash loop. ``/health`` reports which mode is active so the fallback is never silent.
    """
    if settings.provider == "anthropic" and settings.anthropic_api_key:
        return AnthropicProvider(
            api_key=settings.anthropic_api_key,
            model=settings.anthropic_model,
            max_tokens=settings.max_tokens,
            timeout=settings.request_timeout_seconds,
        )
    if settings.provider == "openai" and settings.openai_api_key:
        return OpenAIProvider(
            api_key=settings.openai_api_key,
            model=settings.openai_model,
            max_tokens=settings.max_tokens,
            timeout=settings.request_timeout_seconds,
        )
    return EchoProvider()
