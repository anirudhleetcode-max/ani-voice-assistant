package com.ani.nlu

import com.ani.nlu.dialog.ConversationContext
import com.ani.nlu.intent.IntentClassifier
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.IntentType.*
import com.ani.nlu.intent.SlotKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The language corpus.
 *
 * Every line here is something the target user would plausibly say, in Telugu, English,
 * Tanglish or native script. They are asserted as one batch so a regression reports
 * *every* phrase it broke rather than stopping at the first, which is what makes it
 * practical to keep extending the lexicon.
 */
class IntentClassificationTest {

    private val classifier = IntentClassifier()

    private fun intentOf(utterance: String): IntentType =
        classifier.classify(utterance, ConversationContext.EMPTY).type

    private fun assertAll(cases: List<Pair<String, IntentType>>) {
        val failures = cases.mapNotNull { (utterance, expected) ->
            val actual = intentOf(utterance)
            if (actual == expected) null else "  \"$utterance\"  expected=$expected  actual=$actual"
        }
        assertTrue(
            failures.isEmpty(),
            "${failures.size} of ${cases.size} utterances classified wrongly:\n" + failures.joinToString("\n")
        )
    }

    // =================================================================================
    // Calling — the same request in twelve shapes
    // =================================================================================

    @Test
    fun `calling a contact`() = assertAll(
        listOf(
            "rey amma ki call chey" to CALL_CONTACT,
            "rey amma ki call cheyyi" to CALL_CONTACT,
            "rey amma ki call pettu" to CALL_CONTACT,
            "rey amma ki phone chey" to CALL_CONTACT,
            "rey amma ki phone cheyyi" to CALL_CONTACT,
            "rey ammaki call chey" to CALL_CONTACT,
            "rey amma ki call kottu" to CALL_CONTACT,
            "rey amma ki piluvu" to CALL_CONTACT,
            "rey nanna ki call chey" to CALL_CONTACT,
            "rey daddy ki call chey" to CALL_CONTACT,
            "rey dad ki call chey" to CALL_CONTACT,
            "rey mom ki call chey" to CALL_CONTACT,
            "rey Rahul ki call chey" to CALL_CONTACT,
            "rey Anirudh call chey" to CALL_CONTACT,
            "rey akka ki call chey" to CALL_CONTACT,
            "call mom" to CALL_CONTACT,
            "call Rahul" to CALL_CONTACT,
            "please call my dad" to CALL_CONTACT,
            "hey ani call amma" to CALL_CONTACT,
            "rey call chey" to CALL_CONTACT
        )
    )

    @Test
    fun `contact name is extracted from every calling form`() {
        val forms = listOf(
            "rey amma ki call chey",
            "rey ammaki call chey",
            "rey amma ki phone cheyyi",
            "rey amma ki call pettu",
            "rey amma ki call kottu",
            "call amma"
        )
        for (form in forms) {
            val parsed = classifier.classify(form)
            assertEquals("Amma", parsed[SlotKey.CONTACT_NAME], "contact not extracted from \"$form\"")
        }
    }

    @Test
    fun `calling without a name asks who`() {
        val parsed = classifier.classify("rey call chey")
        assertEquals(CALL_CONTACT, parsed.type)
        assertTrue(SlotKey.CONTACT_NAME in parsed.needsSlots)
        assertTrue(!parsed.isComplete)
    }

    // =================================================================================
    // Messaging
    // =================================================================================

    @Test
    fun `sending a message`() = assertAll(
        listOf(
            "rey Rahul ki message pampu" to SEND_MESSAGE,
            "rey Rahul ki msg pampu" to SEND_MESSAGE,
            "rey Rahul ki message chey" to SEND_MESSAGE,
            "rey Rahul ki text pampu" to SEND_MESSAGE,
            "rey Rahul ki sms pampu" to SEND_MESSAGE,
            "rey amma ki whatsapp lo message pampu" to SEND_MESSAGE,
            "rey amma ki message pampinchu" to SEND_MESSAGE,
            "send a message to Rahul" to SEND_MESSAGE,
            "rey message pampu" to SEND_MESSAGE,
            "rey Rahul ki repu kaluddam ani message pampu" to SEND_MESSAGE
        )
    )

    @Test
    fun `message slots are filled`() {
        val parsed = classifier.classify("rey amma ki whatsapp lo message pampu")
        assertEquals(SEND_MESSAGE, parsed.type)
        assertEquals("Amma", parsed[SlotKey.CONTACT_NAME])
        assertEquals("whatsapp", parsed[SlotKey.MESSAGE_CHANNEL])
        assertTrue(SlotKey.MESSAGE_BODY in parsed.needsSlots, "should still need the body")
    }

    @Test
    fun `quotative ani closes the message body`() {
        val parsed = classifier.classify("rey Rahul ki repu 10 ki kaluddam ani message pampu")
        assertEquals(SEND_MESSAGE, parsed.type)
        assertEquals("repu 10 ki kaluddam", parsed[SlotKey.MESSAGE_BODY])
    }

    // =================================================================================
    // Reading notifications
    // =================================================================================

    @Test
    fun `reading notifications and messages`() = assertAll(
        listOf(
            "rey em messages vachayi" to READ_NOTIFICATIONS,
            "rey em messages vacchayi" to READ_NOTIFICATIONS,
            "rey em message vachindi" to READ_NOTIFICATIONS,
            "rey em notifications vachayi" to READ_NOTIFICATIONS,
            "rey na notifications chaduvu" to READ_NOTIFICATIONS,
            "rey notifications chadavu" to READ_NOTIFICATIONS,
            "rey anni notifications cheppu" to READ_NOTIFICATIONS,
            "rey messages chaduvu" to READ_NOTIFICATIONS,
            "read my notifications" to READ_NOTIFICATIONS,
            "what messages did i get" to READ_NOTIFICATIONS,
            "rey whatsapp lo em messages vachayi" to READ_NOTIFICATIONS,
            "rey missed calls unnaya" to READ_MISSED_CALLS,
            "rey em missed calls vachayi" to READ_MISSED_CALLS
        )
    )

    @Test
    fun `asking about messages prefers messaging apps`() {
        assertEquals("messages", classifier.classify("rey em messages vachayi")[SlotKey.NOTIFICATION_FILTER])
        assertEquals("all", classifier.classify("rey em notifications vachayi")[SlotKey.NOTIFICATION_FILTER])
        assertEquals("whatsapp", classifier.classify("rey whatsapp lo em messages vachayi")[SlotKey.NOTIFICATION_FILTER])
    }

    // =================================================================================
    // Music
    // =================================================================================

    @Test
    fun `playing music`() = assertAll(
        listOf(
            "rey Arijit Singh play chey" to PLAY_MUSIC,
            "rey Arijit Singh songs pettu" to PLAY_MUSIC,
            "rey Arijit Singh paatalu play chey" to PLAY_MUSIC,
            "rey Kesariya play chey" to PLAY_MUSIC,
            "rey Kesariya pettu" to PLAY_MUSIC,
            "rey telugu songs pettu" to PLAY_MUSIC,
            "rey melody songs play chey" to PLAY_MUSIC,
            "rey spotify lo arijit singh play chey" to PLAY_MUSIC,
            "rey spotify lo latest telugu songs play chey" to PLAY_MUSIC,
            "play Arijit Singh" to PLAY_MUSIC,
            "rey oka paata pettu" to PLAY_MUSIC,
            "rey Sid Sriram paata veyyi" to PLAY_MUSIC
        )
    )

    @Test
    fun `music controls`() = assertAll(
        listOf(
            "rey next song" to MUSIC_CONTROL,
            "rey next" to MUSIC_CONTROL,
            "rey previous song" to MUSIC_CONTROL,
            "rey pause chey" to MUSIC_CONTROL,
            "rey pause" to MUSIC_CONTROL,
            "rey continue chey" to MUSIC_CONTROL,
            "rey resume chey" to MUSIC_CONTROL,
            "rey ee song malli play chey" to MUSIC_CONTROL,
            "rey ee paata malli pettu" to MUSIC_CONTROL
        )
    )

    @Test
    fun `music query excludes the verbs and the app`() {
        val parsed = classifier.classify("rey spotify lo Arijit Singh play chey")
        assertEquals(PLAY_MUSIC, parsed.type)
        assertEquals("arijit singh", parsed[SlotKey.MUSIC_QUERY])
        assertEquals("spotify", parsed[SlotKey.MUSIC_PROVIDER])
    }

    @Test
    fun `a singer's name is not mistaken for the word song`() {
        // "singh" folds close to "song"; tolerant matching must not reach that far.
        assertEquals("arijit singh", classifier.classify("rey Arijit Singh play chey")[SlotKey.MUSIC_QUERY])
    }

    // =================================================================================
    // Apps
    // =================================================================================

    @Test
    fun `opening apps`() = assertAll(
        listOf(
            "rey whatsapp open chey" to OPEN_APP,
            "rey WhatsApp open cheyyi" to OPEN_APP,
            "rey wa open chey" to OPEN_APP,
            "rey instagram open chey" to OPEN_APP,
            "rey insta open chey" to OPEN_APP,
            "rey chrome open chey" to OPEN_APP,
            "rey spotify open chey" to OPEN_APP,
            "rey camera open chey" to OPEN_APP,
            "rey google maps open chey" to OPEN_APP,
            "rey calculator open chey" to OPEN_APP,
            "rey na photos open chey" to OPEN_APP,
            "rey downloads open chey" to OPEN_APP,
            "rey youtube teruvu" to OPEN_APP,
            "open Instagram" to OPEN_APP
        )
    )

    @Test
    fun `app nicknames resolve to one canonical name`() {
        for (utterance in listOf("rey whatsapp open chey", "rey wa open chey", "rey watsap open chey")) {
            assertEquals("whatsapp", classifier.classify(utterance)[SlotKey.APP_NAME], utterance)
        }
        assertEquals("instagram", classifier.classify("rey insta open chey")[SlotKey.APP_NAME])
        assertEquals("gallery", classifier.classify("rey na photos open chey")[SlotKey.APP_NAME])
    }

    // =================================================================================
    // Settings and device controls
    // =================================================================================

    @Test
    fun `device controls and settings`() = assertAll(
        listOf(
            "rey flashlight on chey" to CONTROL_FLASHLIGHT,
            "rey flashlight off chey" to CONTROL_FLASHLIGHT,
            "rey torch on chey" to CONTROL_FLASHLIGHT,
            "rey light veliginchu" to CONTROL_FLASHLIGHT,
            "rey battery entha undi" to GET_BATTERY,
            "rey battery ela undi" to GET_BATTERY,
            "how much battery" to GET_BATTERY,
            "rey wifi settings open chey" to OPEN_SETTINGS,
            "rey bluetooth open chey" to OPEN_SETTINGS,
            "rey settings open chey" to OPEN_SETTINGS,
            "rey brightness penchu" to OPEN_SETTINGS,
            "rey volume penchu" to CONTROL_VOLUME,
            "rey volume taggu" to CONTROL_VOLUME,
            "rey sound penchu" to CONTROL_VOLUME,
            "rey do not disturb on chey" to CONTROL_DND,
            "rey dnd off chey" to CONTROL_DND,
            "rey phone silent lo unda" to GET_DEVICE_STATUS,
            "rey storage entha undi" to GET_DEVICE_STATUS
        )
    )

    @Test
    fun `flashlight toggle state is read correctly`() {
        assertEquals("on", classifier.classify("rey flashlight on chey")[SlotKey.TOGGLE_STATE])
        assertEquals("off", classifier.classify("rey flashlight off chey")[SlotKey.TOGGLE_STATE])
    }

    // =================================================================================
    // Alarms, timers, reminders
    // =================================================================================

    @Test
    fun `alarms timers and reminders`() = assertAll(
        listOf(
            "rey repu 7 ki alarm pettu" to SET_ALARM,
            "rey repu morning 7 ki alarm petti" to SET_ALARM,
            "rey repu morning 7 ki nannu lepali" to SET_ALARM,
            "rey repu edu gantalaki lepu" to SET_ALARM,
            "set an alarm for 7" to SET_ALARM,
            "rey 20 minutes timer pettu" to SET_TIMER,
            "rey 10 nimishalu timer pettu" to SET_TIMER,
            "rey evening 6 ki reminder pettu" to CREATE_REMINDER,
            "rey naaku gurthu chey" to CREATE_REMINDER,
            "rey repu gurthu chey" to CREATE_REMINDER,
            "rey tomorrow 10 ki Rahul ki call cheyyali ani gurthu chey" to CREATE_REMINDER,
            "remind me at 6" to CREATE_REMINDER
        )
    )

    @Test
    fun `alarm time is parsed with the day`() {
        val parsed = classifier.classify("rey repu morning 7 ki nannu lepali")
        assertEquals(SET_ALARM, parsed.type)
        val spec = requireNotNull(parsed.timeSpec)
        assertEquals(7, spec.resolvedHour())
        assertEquals(1, spec.dayOffset)
        assertTrue(!spec.isAmbiguous, "morning was stated, so there is nothing to ask")
    }

    @Test
    fun `a bare hour stays ambiguous so Ani can ask`() {
        val spec = requireNotNull(classifier.classify("rey repu 7 ki alarm pettu").timeSpec)
        assertTrue(spec.isAmbiguous, "7 with no AM/PM cue must not be silently resolved")
    }

    @Test
    fun `timer duration is parsed`() {
        val spec = requireNotNull(classifier.classify("rey 20 minutes timer pettu").timeSpec)
        assertEquals(20 * 60L, spec.durationSeconds)
    }

    // =================================================================================
    // Information and conversation
    // =================================================================================

    @Test
    fun `information questions`() = assertAll(
        listOf(
            "rey time entha" to GET_TIME,
            "rey time enti" to GET_TIME,
            "what time is it" to GET_TIME,
            "rey ivala date enti" to GET_DATE,
            "rey ee roju date enti" to GET_DATE,
            "rey weather ela undi" to GET_WEATHER,
            "rey vaatavaranam ela undi" to GET_WEATHER
        )
    )

    @Test
    fun `navigation and location`() = assertAll(
        listOf(
            "rey google maps open chesi college ki route chupinchu" to NAVIGATE,
            "rey college ki navigate chey" to NAVIGATE,
            "rey location share chey" to SHARE_LOCATION
        )
    )

    @Test
    fun `small talk and unknown requests fall back safely`() = assertAll(
        listOf(
            "rey emaina cheppu" to CONVERSATION,
            "rey em chesthunnav" to GENERAL_QUESTION,
            "rey thanks ra" to CONVERSATION,
            "rey nuvvu ela unnav" to GENERAL_QUESTION
        )
    )

    @Test
    fun `bare wake word only acknowledges`() {
        for (utterance in listOf("Rey", "rey", "reyyyy", "Hey Ani", "orey", "Ani")) {
            assertEquals(WAKE_ONLY, intentOf(utterance), utterance)
        }
    }

    // =================================================================================
    // Native Telugu script
    // =================================================================================

    @Test
    fun `native Telugu script reaches the same intents`() = assertAll(
        listOf(
            "అమ్మకి కాల్ చెయ్" to CALL_CONTACT,
            "పాట పెట్టు" to PLAY_MUSIC,
            "టైమ్ ఎంత" to GET_TIME
        )
    )

    @Test
    fun `script and Latin spelling of the same sentence agree`() {
        val script = classifier.classify("అమ్మకి కాల్ చెయ్")
        val latin = classifier.classify("ammaki call chey")
        assertEquals(latin.type, script.type)
        assertEquals(latin[SlotKey.CONTACT_NAME], script[SlotKey.CONTACT_NAME])
    }

    // =================================================================================
    // Robustness
    // =================================================================================

    @Test
    fun `elongation and stray punctuation do not change the intent`() = assertAll(
        listOf(
            "reyyyy amma ki call chey!!" to CALL_CONTACT,
            "Rey... amma ki call chey?" to CALL_CONTACT,
            "REY AMMA KI CALL CHEY" to CALL_CONTACT,
            "rey   amma   ki   call   chey" to CALL_CONTACT
        )
    )

    @Test
    fun `empty and noise input never crashes`() {
        for (utterance in listOf("", "   ", "...", "!!!", "\n")) {
            assertEquals(UNKNOWN, intentOf(utterance), "\"$utterance\"")
        }
    }
}
