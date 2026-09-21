"""OpenAI Chat Completions API."""

from __future__ import annotations

import httpx

from .base import Provider, ProviderError, ReplyRequest

API_URL = "https://api.openai.com/v1/chat/completions"


class OpenAIProvider(Provider):
    name = "openai"

    def __init__(self, api_key: str, model: str, max_tokens: int, timeout: float) -> None:
        self._api_key = api_key
        self._model = model
        self._max_tokens = max_tokens
        self._timeout = timeout

    async def reply(self, request: ReplyRequest, system_prompt: str) -> str:
        messages = [{"role": "system", "content": system_prompt}]
        messages += [
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
            "messages": messages,
        }
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }

        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                response = await client.post(API_URL, json=payload, headers=headers)
        except httpx.HTTPError as error:
            raise ProviderError(f"openai unreachable: {type(error).__name__}") from error

        if response.status_code != 200:
            raise ProviderError(f"openai returned {response.status_code}")

        body = response.json()
        choices = body.get("choices") or []
        if not choices:
            raise ProviderError("openai returned no choices")
        text = (choices[0].get("message") or {}).get("content", "").strip()
        if not text:
            raise ProviderError("openai returned no text")
        return text
