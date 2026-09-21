"""Configuration, read from the environment.

Nothing here has a default that would work in production by accident: with no API key
configured the service starts and answers, but it answers with the ``echo`` provider and
says so in ``/health``. A backend that silently degrades is better than one that refuses
to boot, but only if it is honest about which mode it is in.
"""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="ANI_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    provider: str = "echo"

    anthropic_api_key: str = ""
    anthropic_model: str = "claude-sonnet-5"

    openai_api_key: str = ""
    openai_model: str = "gpt-4o-mini"

    rate_limit_per_minute: int = 20
    max_tokens: int = 200
    request_timeout_seconds: float = 12.0

    @property
    def has_credentials(self) -> bool:
        if self.provider == "anthropic":
            return bool(self.anthropic_api_key)
        if self.provider == "openai":
            return bool(self.openai_api_key)
        return True  # the echo provider needs nothing


@lru_cache
def get_settings() -> Settings:
    return Settings()
