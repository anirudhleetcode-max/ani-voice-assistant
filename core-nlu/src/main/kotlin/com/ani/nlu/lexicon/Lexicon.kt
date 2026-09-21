package com.ani.nlu.lexicon

import com.ani.nlu.lexicon.SemanticTag.*
import com.ani.nlu.text.Fuzzy
import com.ani.nlu.text.PhoneticKey
import com.ani.nlu.text.Token

/**
 * Ani's vocabulary: spoken word -> [SemanticTag]s.
 *
 * Every entry below is written the way a person would *say or type* it; the table is
 * keyed by [PhoneticKey] at construction time, so the many spellings of one word
 * ("cheyyi"/"cheyi"/"chey") usually collapse onto a single key automatically. Spellings
 * that fold differently are simply listed too — the list is cheap, wrong answers are not.
 *
 * Lookup order is exact key, then a length-scaled fuzzy pass. The fuzzy pass is what
 * absorbs recogniser noise ("chestunna" vs "chesthunna", "vaccayi" vs "vachayi").
 */
object Lexicon {

    private val entries: Map<String, Set<SemanticTag>> = buildTable()

    /** Keys sorted once so the fuzzy fallback has a stable, cache-friendly scan order. */
    private val allKeys: List<String> = entries.keys.sorted()

    /** Tags for an already-phonetic [key], exact match only. */
    fun tagsForKey(key: String): Set<SemanticTag> = entries[key].orEmpty()

    /** Tags for a raw word, exact match then fuzzy. */
    fun tagsForWord(word: String): Set<SemanticTag> = tagsFor(PhoneticKey.of(word))

    /**
     * Tags for a phonetic [key], falling back to the closest key within
     * [Fuzzy.toleranceFor]. Returns an empty set for words we do not know — which is the
     * normal case for contact names, song titles and app names, and is exactly how the
     * slot extractors find them.
     */
    fun tagsFor(key: String): Set<SemanticTag> {
        if (key.isEmpty()) return emptySet()
        entries[key]?.let { return it }

        val tolerance = Fuzzy.toleranceFor(key.length)
        if (tolerance == 0) return emptySet()

        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (candidate in allKeys) {
            // Cheap length gate before paying for the edit distance.
            if (kotlin.math.abs(candidate.length - key.length) > tolerance) continue
            val distance = Fuzzy.levenshtein(candidate, key)
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
                if (distance == 1) break
            }
        }
        return if (best != null && bestDistance <= tolerance) entries.getValue(best) else emptySet()
    }

    fun tagsFor(token: Token): Set<SemanticTag> = tagsFor(token.key)

    fun has(token: Token, tag: SemanticTag): Boolean = tag in tagsFor(token)

    /** True when any token in [tokens] carries [tag]. */
    fun anyHas(tokens: List<Token>, tag: SemanticTag): Boolean = tokens.any { has(it, tag) }

    /** First token carrying [tag], or null. */
    fun firstWith(tokens: List<Token>, tag: SemanticTag): Token? = tokens.firstOrNull { has(it, tag) }

    /** True when the word is known to the lexicon at all (used by the language detector). */
    fun isKnown(key: String): Boolean = entries.containsKey(key)

    /** Number of distinct phonetic keys in the table — surfaced in diagnostics. */
    val size: Int get() = entries.size

    // -----------------------------------------------------------------------------
    // Table construction
    // -----------------------------------------------------------------------------

    private class TableBuilder {
        val map = mutableMapOf<String, MutableSet<SemanticTag>>()

        /** Registers every spelling in [words] under [tags]. */
        fun put(vararg tags: SemanticTag, words: String) {
            for (word in words.split(',')) {
                val trimmed = word.trim()
                if (trimmed.isEmpty()) continue
                val key = PhoneticKey.of(trimmed)
                if (key.isEmpty()) continue
                map.getOrPut(key) { mutableSetOf() }.addAll(tags)
            }
        }
    }

    private fun buildTable(): Map<String, Set<SemanticTag>> {
        val b = TableBuilder()

        // ---- Light verbs ---------------------------------------------------------
        b.put(LIGHT_DO, words = "chey, cheyi, cheyyi, che, chesey, chesi, chestha, chesthunna, cheyyandi, cheyyali, cheyu, do, cheyyava, chesko")
        b.put(LIGHT_PUT, words = "pettu, petti, pettandi, pettey, pettuko, petu, peduthu, pedataanu, put, set, pettava, pettali")

        // ---- Calling -------------------------------------------------------------
        b.put(V_CALL, N_CALL, words = "call, kaal, phone, fone, ring, dial, calling")
        b.put(V_CALL, words = "piluvu, pilu, pilavandi, kottu, kalupu, kalipinchu, kalapandi")
        b.put(N_CALL, words = "calls, kaalu")
        b.put(N_MISSED, words = "missed, miss, misdu")

        // ---- Messaging -----------------------------------------------------------
        b.put(V_SEND, words = "pampu, pampinchu, pampandi, pampali, pampana, pamp, send, sendu, forward, pamputhunna")
        b.put(N_MESSAGE, words = "message, messages, msg, msgs, sms, text, texts, sandesam, sandeshalu, mesej")
        b.put(V_SHARE, words = "share, pancu")

        // ---- Notifications -------------------------------------------------------
        b.put(N_NOTIFICATION, words = "notification, notifications, notif, notifs, alert, alerts, notifikeshan")

        // ---- Music ---------------------------------------------------------------
        b.put(V_PLAY, words = "play, vinipinchu, vinipichu, veyyi, vey, veyu, playing")
        b.put(N_MUSIC, words = "song, songs, paata, paatalu, pata, patalu, music, track, tracks, audio, tune, geetam, geethalu, songu")
        b.put(N_MUSIC, words = "album, playlist, playlists, albums")
        b.put(N_ARTIST, words = "singer, artist, gayakudu, gaayakudu, singers")
        b.put(V_PAUSE, words = "pause, hold, aapey")
        b.put(V_RESUME, words = "resume, continue, konasaginchu, konaginchu")
        b.put(V_NEXT, words = "next, tarvata, tarvati, munduku, skip")
        b.put(V_PREVIOUS, words = "previous, back, venakki, venaka, mundadi, prev")

        // ---- Generic app / navigation verbs --------------------------------------
        b.put(V_OPEN, words = "open, teruvu, terchu, terichi, launch, opening, openu")
        b.put(V_CLOSE, words = "close, mooyi, moyyi, band, closing, kottesey")
        b.put(V_STOP, words = "stop, aapu, apu, aagu, agu, apandi, aapey, cancel, aapali, ninchey")
        b.put(V_SEARCH, words = "search, vetuku, vethuku, find, vedaku, vetakandi, kanukko")
        b.put(V_SHOW, words = "show, chupinchu, chupu, choodu, chudu, chudandi, choopinchu")
        b.put(V_NAVIGATE, words = "navigate, navigation, route, daari, dari, direction, directions")

        // ---- Reading / telling ---------------------------------------------------
        b.put(V_READ, words = "read, chaduvu, chadu, chadavu, chadavandi, chadivi, reading")
        b.put(V_TELL, words = "cheppu, chepu, cheppandi, cheppava, tell, say, cheppara, telusuko")

        // ---- Alarms, timers, reminders -------------------------------------------
        b.put(N_ALARM, words = "alarm, alarams, alaram, alarms")
        b.put(N_TIMER, words = "timer, timers, taimar")
        b.put(N_REMINDER, words = "reminder, reminders, rimaindar")
        b.put(V_REMIND, N_REMINDER, words = "gurthu, gurtu, gurthuchey, gnyapakam, jnapakam, remind, gurthupettu")
        b.put(V_WAKE, words = "lepu, lepali, levali, lepandi, lepara, wake, wakeup")

        // ---- Device status and controls ------------------------------------------
        b.put(N_BATTERY, words = "battery, batri, batari, charge, charging")
        b.put(N_FLASHLIGHT, words = "flashlight, flash, torch, tarch, tarchlight, light, laitu")
        b.put(N_WIFI, words = "wifi, wi fi, vaifai, internet, net")
        b.put(N_BLUETOOTH, words = "bluetooth, blutooth, bt")
        b.put(N_VOLUME, words = "volume, sound, saundu, valyum, shabdam")
        b.put(N_BRIGHTNESS, words = "brightness, bright, brightnes, velugu")
        b.put(N_DND, words = "dnd, disturb, silent, silentlo")
        b.put(N_AIRPLANE, words = "airplane, aeroplane")
        b.put(N_STORAGE, words = "storage, memory, space, jagha")
        b.put(N_NETWORK, words = "network, signal, data")
        b.put(N_LOCATION, words = "location, gps, chota, prantham")
        b.put(N_MAPS, words = "maps, map, mapsu")
        b.put(N_SETTINGS, words = "settings, setting, settingsu, settinglu")
        b.put(N_PHONE, words = "phone, mobile, cell, device, fonu")
        b.put(N_APP, words = "app, apps, application, yaap")
        b.put(V_TURN_ON, words = "on, veliginchu, velaginchu, enable, onchey, onn")
        b.put(V_TURN_OFF, words = "off, arpu, aripu, aarpu, disable, offchey, offu")
        b.put(V_INCREASE, words = "penchu, pencha, penchandi, increase, up, ekkuva, ekkuvachey, raise")
        b.put(V_DECREASE, words = "taggu, tagginchu, tagginchandi, decrease, down, takkuva, reduce, lower")

        // ---- Information ---------------------------------------------------------
        b.put(N_TIME, words = "time, samayam, gadiyaram, taimu, taim")
        b.put(N_DATE, words = "date, tedi, tareekhu, taarikhu, dinam")
        b.put(N_WEATHER, words = "weather, vaatavaranam, vatavaranam, vana, varsham, endaa, ushnogratha")

        // ---- Teaching / memory ---------------------------------------------------
        b.put(V_TEACH, words = "nerpinchu, nerpu, teach, nerpinchandi, save")
        b.put(V_REMEMBER, words = "gurthupettuko, gurtupettuko, remember, note")
        b.put(V_DELETE, words = "delete, tholagichu, tolaginchu, remove, teesey, theesey, clear")

        // ---- Kinship terms (a strong signal that a contact name follows) ----------
        b.put(N_KINSHIP, words = "amma, nanna, naanna, daddy, dad, mom, mummy, papa, akka, anna, thammudu, tammudu, chelli, chellelu, babai, pinni, mama, attha, atta, tata, tathayya, ammamma, nayanamma, bava, vadina, maradalu, wife, husband, bharya, bharta")

        // ---- Wake tokens and fillers ---------------------------------------------
        b.put(F_WAKE, words = "rey, orey, ore, ani, arey, aney")
        b.put(F_FILLER, words = "ra, raa, oi, oye, abba, ayyo, please, plz, konchem, koncham, mari, anta, kada, kadha, bro, macha, guru, inka, andi, sir")
        // Demonstratives carry no content for us but would otherwise leak into search
        // queries: "ee song malli pettu" must not search for a song called "ee".
        b.put(F_FILLER, words = "ee, aa, idi, adi, ide, ade, ivi, avi, this, that, it, them")

        // ---- Yes / no ------------------------------------------------------------
        b.put(F_AFFIRM, words = "avunu, aunu, avnu, ava, sare, sari, seri, sarle, ok, okay, oke, yes, yeah, yep, ha, haan, correct, right, confirm, done")
        b.put(F_DENY, F_NEGATION, words = "kaadu, kadu, vaddu, vodhu, vaddhu, venda, no, nope, cancel, vaddandi, stop")
        b.put(F_NEGATION, words = "ledu, ledhu, leda, not")

        // ---- Question words ------------------------------------------------------
        b.put(F_QUESTION, words = "enti, entii, em, emi, emiti, emito, entha, enthaa, ela, elaa, ekkada, eppudu, enduku, evaru, edi, what, when, where, why, who, which, how")
        // Telugu turns a statement into a question with a final vowel: "undi" (it is) vs
        // "unda" (is it?). Without these, "phone silent lo unda?" reads as a command.
        b.put(F_QUESTION, words = "unda, undaa, undhaa, unnaya, unnayaa, ayyinda, ayindaa, ledaa")

        // ---- Misc function words -------------------------------------------------
        b.put(F_QUOTATIVE, words = "ani, ane")
        b.put(F_AGAIN, V_REPEAT, words = "malli, malla, again, repeat, marokasari, inkosari, replay")
        b.put(F_SELF, words = "nenu, naaku, naku, nannu, na, naa, me, my, mine, nadi, naadi")
        b.put(F_ALL, words = "anni, annee, all, every, prathi")

        // ---- Time words ----------------------------------------------------------
        b.put(T_TODAY, words = "ivala, ivaala, ivaal, eeroju, today, nedu")
        b.put(T_TOMORROW, words = "repu, rapu, tomorrow")
        b.put(T_YESTERDAY, words = "ninna, nenna, yesterday")
        b.put(T_NOW, words = "ippudu, ipudu, now, immediately, ventane, vemtane")
        b.put(T_MORNING, words = "morning, udayam, poddunna, podduna, poddunne, am")
        b.put(T_AFTERNOON, words = "afternoon, madhyanam, madhyahnam, madyanam")
        b.put(T_EVENING, words = "evening, sayantram, saayantram, sayantranam, pm")
        b.put(T_NIGHT, words = "night, ratri, raatri, raatrri, tonight")
        b.put(T_HOUR_UNIT, words = "ganta, gantalu, gantalaki, gantaki, gantala, hour, hours, oclock, o clock")
        b.put(T_MINUTE_UNIT, words = "nimisham, nimishalu, nimishaalu, nimisha, minute, minutes, mins, min")
        b.put(T_SECOND_UNIT, words = "sekanu, sekanlu, second, seconds, secs, sec")
        b.put(T_DAY_UNIT, words = "roju, rojulu, day, days")
        b.put(T_AFTER, words = "tarvata, taruvata, taruvatha, after, later, tarvatha, lopu")
        b.put(
            T_WEEKDAY,
            words = "monday, tuesday, wednesday, thursday, friday, saturday, sunday, " +
                "somavaram, mangalavaram, budhavaram, guruvaram, shukravaram, shanivaram, adivaram"
        )

        // ---- Number words (kept as tags; values live in Numbers) ------------------
        for (word in Numbers.allWords()) b.put(F_NUMBER_WORD, words = word)

        return b.map.mapValues { it.value.toSet() }
    }
}
