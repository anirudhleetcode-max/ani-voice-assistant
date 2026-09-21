"""Backend tests.

They run against the echo provider, so they need no API key and never make a network
call — which is what makes them worth running in CI.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.main import app
from app.prompts import build_system_prompt
from app.providers import build_provider
from app.providers.anthropic import _collapse_consecutive_roles
from app.ratelimit import RateLimiter


@pytest.fixture
def client() -> TestClient:
    app.dependency_overrides[get_settings] = lambda: Settings(
        provider="echo", rate_limit_per_minute=1000
    )
    if hasattr(app.state, "limiter"):
        del app.state.limiter
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()


def test_health_reports_the_active_provider(client: TestClient) -> None:
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert body["provider"] == "echo"


def test_reply_returns_a_sentence(client: TestClient) -> None:
    response = client.post("/v1/reply", json={"text": "rey em chesthunnav"})
    assert response.status_code == 200
    assert response.json()["reply"]


def test_reply_answers_english_in_english(client: TestClient) -> None:
    body = client.post(
        "/v1/reply", json={"text": "what is the capital of France", "language": "ENGLISH"}
    ).json()
    assert "phone commands" in body["reply"]


def test_empty_text_is_rejected(client: TestClient) -> None:
    assert client.post("/v1/reply", json={"text": ""}).status_code == 422


def test_oversized_text_is_rejected(client: TestClient) -> None:
    assert client.post("/v1/reply", json={"text": "x" * 5000}).status_code == 422


def test_too_many_turns_is_rejected(client: TestClient) -> None:
    turns = [{"role": "user", "text": "hi"} for _ in range(50)]
    response = client.post("/v1/reply", json={"text": "hi", "turns": turns})
    assert response.status_code == 422


def test_rate_limit_is_enforced_per_install() -> None:
    app.dependency_overrides[get_settings] = lambda: Settings(
        provider="echo", rate_limit_per_minute=2
    )
    if hasattr(app.state, "limiter"):
        del app.state.limiter

    with TestClient(app) as test_client:
        headers = {"X-Ani-Install": "install-a"}
        assert test_client.post("/v1/reply", json={"text": "hi"}, headers=headers).status_code == 200
        assert test_client.post("/v1/reply", json={"text": "hi"}, headers=headers).status_code == 200
        assert test_client.post("/v1/reply", json={"text": "hi"}, headers=headers).status_code == 429

        # A different install has its own bucket.
        other = {"X-Ani-Install": "install-b"}
        assert test_client.post("/v1/reply", json={"text": "hi"}, headers=other).status_code == 200

    app.dependency_overrides.clear()


def test_rate_limiter_window_rolls() -> None:
    limiter = RateLimiter(per_minute=2)
    assert limiter.allow("k", now=0.0)
    assert limiter.allow("k", now=1.0)
    assert not limiter.allow("k", now=2.0)
    # A minute later the window has rolled.
    assert limiter.allow("k", now=70.0)


def test_system_prompt_tells_the_model_it_cannot_act() -> None:
    prompt = build_system_prompt("TELUGU", "FRIENDLY")
    assert "no ability to call anyone" in prompt
    assert "never say you have done something" in prompt.lower()


def test_system_prompt_follows_the_spoken_language() -> None:
    assert "Reply in English" in build_system_prompt("ENGLISH", "FRIENDLY")
    assert "Tanglish" in build_system_prompt("TELUGU", "FRIENDLY")


def test_persona_changes_the_prompt() -> None:
    professional = build_system_prompt("MIXED", "PROFESSIONAL")
    assert "Drop 'ra'" in professional


def test_missing_credentials_fall_back_to_echo() -> None:
    provider = build_provider(Settings(provider="anthropic", anthropic_api_key=""))
    assert provider.name == "echo"


def test_credentials_select_the_real_provider() -> None:
    provider = build_provider(Settings(provider="anthropic", anthropic_api_key="sk-test"))
    assert provider.name == "anthropic"


def test_consecutive_same_role_turns_are_merged() -> None:
    merged = _collapse_consecutive_roles(
        [
            {"role": "user", "content": "one"},
            {"role": "user", "content": "two"},
            {"role": "assistant", "content": "ok"},
        ]
    )
    assert len(merged) == 2
    assert merged[0]["content"] == "one\ntwo"
