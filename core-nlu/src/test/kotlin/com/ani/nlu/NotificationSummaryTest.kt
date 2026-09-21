package com.ani.nlu

import com.ani.nlu.response.NotificationItem
import com.ani.nlu.response.NotificationPrivacy
import com.ani.nlu.response.NotificationSummarizer
import com.ani.nlu.response.Persona
import com.ani.nlu.response.ResponseStyle
import com.ani.nlu.text.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationSummaryTest {

    private val telugu = ResponseStyle(language = Language.TELUGU, persona = Persona.FRIENDLY)
    private val english = ResponseStyle(language = Language.ENGLISH, persona = Persona.FRIENDLY)

    private fun whatsapp(sender: String, preview: String? = null) = NotificationItem(
        appCanonical = "whatsapp",
        appLabel = "WhatsApp",
        sender = sender,
        preview = preview
    )

    @Test
    fun `an empty inbox says so plainly`() {
        val summary = NotificationSummarizer.summarize(emptyList(), NotificationPrivacy.SENDER_ONLY, telugu)
        assertTrue(summary.contains("ledu"), summary)
    }

    @Test
    fun `messages are grouped by app and then by sender`() {
        val items = listOf(whatsapp("Amma"), whatsapp("Rahul"), whatsapp("Rahul"))
        val summary = NotificationSummarizer.summarize(items, NotificationPrivacy.SENDER_ONLY, telugu)
        // "WhatsApp lo moodu messages vachayi. Rahul nundi rendu, Amma nundi okati."
        assertTrue(summary.contains("WhatsApp"), summary)
        assertTrue(summary.contains("moodu"), summary)
        assertTrue(summary.contains("Rahul nundi rendu"), summary)
        assertTrue(summary.contains("Amma nundi okati"), summary)
    }

    @Test
    fun `count only privacy never names anyone`() {
        val items = listOf(whatsapp("Amma"), whatsapp("Rahul"))
        val summary = NotificationSummarizer.summarize(items, NotificationPrivacy.COUNT_ONLY, telugu)
        assertTrue(!summary.contains("Amma"), summary)
        assertTrue(!summary.contains("Rahul"), summary)
        assertTrue(summary.contains("rendu"), summary)
    }

    @Test
    fun `previews are only read when the user turned them on`() {
        val items = listOf(whatsapp("Amma", "repu vasthunna"))
        val withoutPreview =
            NotificationSummarizer.summarize(items, NotificationPrivacy.SENDER_ONLY, telugu)
        val withPreview =
            NotificationSummarizer.summarize(items, NotificationPrivacy.SENDER_AND_PREVIEW, telugu)
        assertTrue(!withoutPreview.contains("repu vasthunna"), withoutPreview)
        assertTrue(withPreview.contains("repu vasthunna"), withPreview)
    }

    @Test
    fun `English summaries read naturally too`() {
        val items = listOf(whatsapp("Amma"), whatsapp("Rahul"), whatsapp("Rahul"))
        val summary = NotificationSummarizer.summarize(items, NotificationPrivacy.SENDER_ONLY, english)
        assertTrue(summary.contains("3 messages on WhatsApp"), summary)
        assertTrue(summary.contains("2 from Rahul"), summary)
    }

    @Test
    fun `a long list is truncated rather than read out in full`() {
        val items = (1..6).map {
            NotificationItem("app$it", "App $it", sender = "Someone $it")
        }
        val summary = NotificationSummarizer.summarize(items, NotificationPrivacy.SENDER_ONLY, telugu)
        assertTrue(summary.contains("Inka 3 notifications"), summary)
    }

    @Test
    fun `the spoken filter selects which notifications are considered`() {
        val items = listOf(
            whatsapp("Amma"),
            NotificationItem("instagram", "Instagram", sender = "someone", isMessaging = false),
            NotificationItem("missed_calls", "Missed calls", sender = "Rahul")
        )
        assertEquals(3, NotificationSummarizer.applyFilter(items, "all").size)
        assertEquals(2, NotificationSummarizer.applyFilter(items, "messages").size)
        assertEquals(1, NotificationSummarizer.applyFilter(items, "whatsapp").size)
        assertEquals(1, NotificationSummarizer.applyFilter(items, "missed_calls").size)
    }
}
