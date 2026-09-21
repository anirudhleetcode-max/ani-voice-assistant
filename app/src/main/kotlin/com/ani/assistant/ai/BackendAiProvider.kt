package com.ani.assistant.ai

import com.ani.assistant.core.log.AniLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
private data class ReplyRequestBody(
    val text: String,
    val language: String,
    val persona: String,
    val turns: List<TurnBody>,
    val context: String? = null
)

@Serializable
private data class TurnBody(val role: String, val text: String)

@Serializable
private data class ReplyResponseBody(val reply: String? = null, val error: String? = null)

/**
 * Talks to Ani's own backend, which holds the model API key.
 *
 * The contract is one endpoint, `POST /v1/reply`, taking the user's sentence and returning
 * a sentence. The backend decides which model answers and enforces the system prompt that
 * keeps Ani in character; see `backend/` and AI_INTEGRATION.md.
 *
 * The [installId] header is an opaque random value generated on first run, used by the
 * backend for rate limiting. It is not an account, it is not tied to the user's identity,
 * and it can be regenerated from the Privacy Center.
 */
class BackendAiProvider(
    private val baseUrl: String,
    private val installId: String,
    private val client: OkHttpClient = defaultClient()
) : AiProvider {

    override val id: String = "backend"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun reply(request: AiRequest): AiOutcome = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext AiOutcome.Unavailable("No AI backend is configured.")
        }

        val body = ReplyRequestBody(
            text = request.userText,
            language = request.language.name,
            persona = request.persona.name,
            turns = request.recentTurns.map {
                TurnBody(role = if (it.fromUser) "user" else "assistant", text = it.text)
            },
            context = request.factualContext
        )

        val httpRequest = Request.Builder()
            .url(baseUrl.trimEnd('/') + REPLY_PATH)
            .addHeader("X-Ani-Install", installId)
            .addHeader("Accept", "application/json")
            .post(json.encodeToString(ReplyRequestBody.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(httpRequest).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    AniLog.w(TAG, "backend rejected request", "code" to response.code)
                    return@withContext when (response.code) {
                        429 -> AiOutcome.Unavailable("Too many requests just now.")
                        in 500..599 -> AiOutcome.Failed("The assistant service is having trouble.")
                        else -> AiOutcome.Failed("The assistant service refused that request.")
                    }
                }
                val parsed = json.decodeFromString(ReplyResponseBody.serializer(), payload)
                val text = parsed.reply?.takeIf { it.isNotBlank() }
                    ?: return@withContext AiOutcome.Failed(parsed.error ?: "Empty reply.")
                AiOutcome.Reply(text.trim())
            }
        } catch (error: IOException) {
            // Offline is the common case here and is not worth an error-shaped message.
            AniLog.d(TAG, "backend unreachable")
            AiOutcome.Unavailable("No internet connection.")
        } catch (error: Exception) {
            AniLog.e(TAG, "backend call failed", error)
            AiOutcome.Failed("Could not reach the assistant service.")
        }
    }

    override suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false
        try {
            val request = Request.Builder().url(baseUrl.trimEnd('/') + HEALTH_PATH).get().build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (error: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "AniBackend"
        private const val REPLY_PATH = "/v1/reply"
        private const val HEALTH_PATH = "/health"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Short timeouts on purpose. A voice assistant that pauses for thirty seconds has
         * already failed, whatever it eventually says — better to fall back to the
         * offline reply and tell the user the network is slow.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
