"""The provider abstraction.

Swapping the model behind Ani is a configuration change here, not an app release. That is
the only sane place for the choice to live: the Android client does not know or care which
model answered, and it must never hold a key that would let it ask one directly.
"""

from __future__ import annotations

import abc
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Turn:
    role: str  # "user" or "assistant"
    text: str


@dataclass(frozen=True)
class ReplyRequest:
    text: str
    language: str = "MIXED"
    persona: str = "FRIENDLY"
    turns: list[Turn] = field(default_factory=list)
    context: str | None = None


class ProviderError(RuntimeError):
    """The upstream model could not answer. The message is safe to log, not to speak."""


class Provider(abc.ABC):
    name: str

    @abc.abstractmethod
    async def reply(self, request: ReplyRequest, system_prompt: str) -> str:
        """Return one short sentence. Raise :class:`ProviderError` on failure."""


class EchoProvider(Provider):
    """A provider with no upstream at all.

    Used when no API key is configured, and by the test suite. It never pretends to be a
    model: it returns a fixed line saying the assistant cannot answer open questions right
    now, which is exactly what the app should speak in that situation.
    """

    name = "echo"

    async def reply(self, request: ReplyRequest, system_prompt: str) -> str:
        if request.language.upper() == "ENGLISH":
            return "I can't answer that one right now, but phone commands still work."
        return "Adi naaku ippudu teliyadu ra, kaani phone commands anni pani chesthayi."
