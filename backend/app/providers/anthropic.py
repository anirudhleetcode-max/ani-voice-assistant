"""Anthropic Messages API."""

from __future__ import annotations

import httpx

from .base import Provider, ProviderError, ReplyRequest

API_URL = "https://api.anthropic.com/v1/messages"
API_VERSION = "2023-06-01"


class AnthropicProvider(Provider):
    name = "anthropic"

    def __init__(self, api_key: str, model: str, max_tokens: int, timeout: float) -> None:
        self._api_key = api_key
        self._model = model
        self._max_tokens = max_tokens
        self._timeout = timeout

    async def reply(self, request: ReplyRequest, system_prompt: str) -> str:
        messages = [
            {"role": turn.role, "content": turn.text}
            for turn in request.turns
            if turn.text.strip()
        ]
        user_text = request.text
        if request.context:
            user_text = f"{user_text}\n\n(Known: {request.context})"
        messages.append({"role": "user", "content": user_text})

        payload = {
            "model": self._model,
            "max_tokens": self._max_tokens,
            "system": system_prompt,
            "messages": _collapse_consecutive_roles(messages),
        }
        headers = {
            "x-api-key": self._api_key,
            "anthropic-version": API_VERSION,
            "content-type": "application/json",
        }

        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                response = await client.post(API_URL, json=payload, headers=headers)
        except httpx.HTTPError as error:  # network, DNS, timeout
            raise ProviderError(f"anthropic unreachable: {type(error).__name__}") from error

        if response.status_code != 200:
            raise ProviderError(f"anthropic returned {response.status_code}")

        body = response.json()
        blocks = body.get("content") or []
        text = "".join(
            block.get("text", "") for block in blocks if block.get("type") == "text"
        ).strip()
        if not text:
            raise ProviderError("anthropic returned no text")
        return text


def _collapse_consecutive_roles(messages: list[dict]) -> list[dict]:
    """The Messages API rejects two turns in a row from the same role.

    Ani's transcript can contain them legitimately — an error the user never replied to,
    say — so they are merged rather than dropped.
    """
    collapsed: list[dict] = []
    for message in messages:
        if collapsed and collapsed[-1]["role"] == message["role"]:
            collapsed[-1]["content"] = f"{collapsed[-1]['content']}\n{message['content']}"
        else:
            collapsed.append(dict(message))
    return collapsed
