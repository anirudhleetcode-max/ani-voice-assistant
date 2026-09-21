package com.ani.nlu.intent

import com.ani.nlu.text.PhoneticKey
import com.ani.nlu.text.Token

/**
 * Canonical names for apps people ask for by nickname.
 *
 * This layer does *not* know package names and deliberately never will — which app is
 * installed, and which package a name maps to, is a device question answered by the
 * Android layer against `PackageManager` plus the user's own aliases. All this does is
 * collapse "insta"/"instagram"/"inst" onto one token so the resolver has something
 * stable to look up.
 */
object KnownApps {

    /** canonical name -> the ways people say it. */
    private val aliases: Map<String, List<String>> = mapOf(
        "whatsapp" to listOf("whatsapp", "whats app", "wa", "watsap", "vatsap", "whatapp"),
        "instagram" to listOf("instagram", "insta", "ig", "instagaram"),
        "spotify" to listOf("spotify", "spotfy", "spoti"),
        "youtube" to listOf("youtube", "yt", "you tube"),
        "youtubemusic" to listOf("youtube music", "ytmusic"),
        "chrome" to listOf("chrome", "browser", "google chrome"),
        "gmail" to listOf("gmail", "mail", "email"),
        "maps" to listOf("google maps", "maps", "gmaps", "mapsu"),
        "camera" to listOf("camera", "kamera"),
        "gallery" to listOf("gallery", "photos", "google photos", "photo"),
        "telegram" to listOf("telegram", "tg"),
        "facebook" to listOf("facebook", "fb"),
        "snapchat" to listOf("snapchat", "snap"),
        "netflix" to listOf("netflix"),
        "hotstar" to listOf("hotstar", "disney hotstar"),
        "phonepe" to listOf("phonepe", "phone pe"),
        "gpay" to listOf("gpay", "google pay"),
        "paytm" to listOf("paytm"),
        "zomato" to listOf("zomato"),
        "swiggy" to listOf("swiggy"),
        "uber" to listOf("uber"),
        "ola" to listOf("ola"),
        "calculator" to listOf("calculator", "calc"),
        "clock" to listOf("clock", "alarm clock"),
        "calendar" to listOf("calendar", "kyalendar"),
        "settings" to listOf("settings", "setting"),
        "playstore" to listOf("play store", "playstore", "google play"),
        "files" to listOf("files", "downloads", "file manager"),
        "contacts" to listOf("contacts", "phonebook"),
        "gaana" to listOf("gaana"),
        "wynk" to listOf("wynk"),
        "jiosaavn" to listOf("jiosaavn", "saavn")
    )

    private val byKey: Map<String, String> = buildMap {
        for ((canonical, spellings) in aliases) {
            for (spelling in spellings) {
                val key = PhoneticKey.of(spelling)
                if (key.isNotEmpty()) putIfAbsent(key, canonical)
            }
        }
    }

    /** Apps that can actually play music, used to pick a [SlotKey.MUSIC_PROVIDER]. */
    val musicProviders: Set<String> =
        setOf("spotify", "youtube", "youtubemusic", "gaana", "wynk", "jiosaavn")

    fun canonicalOrNull(word: String): String? = byKey[PhoneticKey.of(word)]

    fun canonicalOrNull(token: Token): String? = byKey[token.key]

    fun isKnownApp(token: Token): Boolean = byKey.containsKey(token.key)

    fun isMusicProvider(token: Token): Boolean = canonicalOrNull(token) in musicProviders

    /** Every canonical app name Ani knows a nickname for. Used by the alias settings UI. */
    fun allCanonicalNames(): List<String> = aliases.keys.sorted()

    /** The spellings registered for [canonical], for showing in the alias editor. */
    fun spellingsOf(canonical: String): List<String> = aliases[canonical].orEmpty()
}
