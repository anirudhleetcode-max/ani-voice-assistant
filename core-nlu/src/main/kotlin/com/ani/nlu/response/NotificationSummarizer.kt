package com.ani.nlu.response

/** How much of a notification the user allowed Ani to say out loud. */
enum class NotificationPrivacy {
    /** "WhatsApp lo 3 messages vachayi." Nothing about who or what. */
    COUNT_ONLY,

    /** Adds who they were from. The default. */
    SENDER_ONLY,

    /** Adds the preview text. Only if the user explicitly turned it on. */
    SENDER_AND_PREVIEW
}

/** One notification, already stripped of anything Ani is not allowed to keep. */
data class NotificationItem(
    /** Canonical app name, e.g. "whatsapp". */
    val appCanonical: String,
    /** Label to say out loud, e.g. "WhatsApp". */
    val appLabel: String,
    val sender: String? = null,
    val preview: String? = null,
    val postedAtEpochMillis: Long = 0L,
    /** True for chat/SMS/mail apps, which "em messages vachayi" asks about. */
    val isMessaging: Boolean = true
)

/**
 * Turns a pile of notifications into one sentence a person would actually say.
 *
 * The shape Ani aims for is the one in the product brief:
 * *"WhatsApp lo 3 messages vachayi. Amma nundi okati, Rahul nundi rendu."*
 *
 * Grouping by app and then by sender is what makes that readable; reading twelve raw
 * notification titles aloud is not a summary, it is a punishment.
 */
object NotificationSummarizer {

    /** Telugu has real words for small counts and speakers use them, not digits. */
    private val teluguCounts = mapOf(
        1 to "okati", 2 to "rendu", 3 to "moodu", 4 to "naalugu", 5 to "aidu",
        6 to "aaru", 7 to "edu", 8 to "enimidi", 9 to "tommidi", 10 to "padi"
    )

    /** How many apps to mention before summarising the rest as a count. */
    private const val MAX_APPS_SPOKEN = 3

    /** How many senders to name per app. */
    private const val MAX_SENDERS_SPOKEN = 3

    fun summarize(
        items: List<NotificationItem>,
        privacy: NotificationPrivacy,
        style: ResponseStyle
    ): String {
        if (items.isEmpty()) return Responses.noNotifications(style)

        val byApp = items.groupBy { it.appLabel }
            .toList()
            .sortedByDescending { (_, group) -> group.size }

        val spoken = byApp.take(MAX_APPS_SPOKEN)
        val remaining = byApp.drop(MAX_APPS_SPOKEN).sumOf { (_, group) -> group.size }

        val sentences = spoken.map { (appLabel, group) ->
            appSentence(appLabel, group, privacy, style)
        }.toMutableList()

        if (remaining > 0) {
            sentences += if (style.speaksTelugu) {
                "Inka $remaining notifications unnayi."
            } else {
                "And $remaining more."
            }
        }
        return sentences.joinToString(" ")
    }

    private fun appSentence(
        appLabel: String,
        group: List<NotificationItem>,
        privacy: NotificationPrivacy,
        style: ResponseStyle
    ): String {
        val count = group.size
        val telugu = style.speaksTelugu
        val head = if (telugu) {
            "$appLabel lo ${countWord(count, style)} ${if (count == 1) "message" else "messages"} vach${if (count == 1) "india" else "ayi"}."
        } else {
            "$count ${if (count == 1) "message" else "messages"} on $appLabel."
        }

        if (privacy == NotificationPrivacy.COUNT_ONLY) return head

        val bySender = group.filter { !it.sender.isNullOrBlank() }
            .groupBy { it.sender!! }
            .toList()
            .sortedByDescending { (_, messages) -> messages.size }
        if (bySender.isEmpty()) return head

        if (privacy == NotificationPrivacy.SENDER_AND_PREVIEW && count <= 2) {
            val details = group.mapNotNull { item ->
                val sender = item.sender ?: return@mapNotNull null
                val preview = item.preview?.takeIf { it.isNotBlank() } ?: return@mapNotNull sender
                if (telugu) "$sender: \"$preview\"" else "$sender says \"$preview\""
            }
            if (details.isNotEmpty()) return "$head ${details.joinToString(". ")}."
        }

        val named = bySender.take(MAX_SENDERS_SPOKEN).map { (sender, messages) ->
            if (telugu) {
                "$sender nundi ${countWord(messages.size, style)}"
            } else {
                "${messages.size} from $sender"
            }
        }
        val others = bySender.drop(MAX_SENDERS_SPOKEN).sumOf { (_, messages) -> messages.size }
        val tail = buildList {
            addAll(named)
            if (others > 0) add(if (telugu) "inka $others" else "$others more")
        }.joinToString(", ")

        return "$head $tail."
    }

    private fun countWord(count: Int, style: ResponseStyle): String =
        if (style.speaksTelugu) teluguCounts[count] ?: count.toString() else count.toString()

    /** Filters a notification list the way the user's spoken filter asked. */
    fun applyFilter(items: List<NotificationItem>, filter: String): List<NotificationItem> =
        when (filter) {
            "all" -> items
            "messages" -> items.filter { it.isMessaging }
            "missed_calls" -> items.filter { it.appCanonical == "missed_calls" }
            else -> items.filter { it.appCanonical.equals(filter, ignoreCase = true) }
        }
}
