package com.ani.nlu.response

import com.ani.nlu.time.TimeSpec

/**
 * Everything Ani says.
 *
 * All user-facing wording lives here so it can be reviewed as *speech* rather than found
 * scattered through the tool implementations. Two rules govern every line:
 *
 *  1. Say it the way a friend would. "Spotify open chesa ra", not "I have successfully
 *     opened Spotify."
 *  2. Never claim something happened that did not. The failure lines below are as
 *     carefully written as the success ones, because an assistant that says "chesa" when
 *     it did nothing is worse than one that says it cannot.
 */
object Responses {

    private fun tel(style: ResponseStyle) = style.speaksTelugu

    // ---- Greetings and acknowledgements -----------------------------------------------

    /** Answer to a bare wake word. */
    fun wakeAcknowledgement(style: ResponseStyle): String = when {
        !tel(style) -> when (style.persona) {
            Persona.MINIMAL -> "Yes?"
            Persona.PROFESSIONAL -> "Yes, how can I help?"
            else -> "Yeah, tell me."
        }
        else -> when (style.persona) {
            Persona.MINIMAL -> "Cheppu."
            Persona.PROFESSIONAL -> "Cheppandi."
            Persona.CHILL -> "Ha cheppu."
            else -> "Cheppu ra."
        }
    }

    fun working(style: ResponseStyle): String =
        if (tel(style)) "Chesthunna${style.particle}." else "On it."

    fun acknowledged(style: ResponseStyle): String =
        if (tel(style)) "Sare${style.particle}." else "Okay."

    // ---- Calling -----------------------------------------------------------------------

    fun callingContact(name: String, style: ResponseStyle): String =
        if (tel(style)) "$name ki call chesthunna${style.particle}." else "Calling $name."

    fun confirmCall(name: String, style: ResponseStyle): String =
        if (tel(style)) "$name ki call cheyyala?" else "Call $name?"

    fun whichContact(style: ResponseStyle): String =
        if (tel(style)) "Evariki call cheyyali?" else "Who should I call?"

    fun contactNotFound(name: String, style: ResponseStyle): String =
        if (tel(style)) "$name ane contact dorakaledu${style.particle}."
        else "I couldn't find a contact called $name."

    /** Several matches for one name: "Rahul ki rendu numbers unnayi..." */
    fun multipleNumbers(name: String, count: Int, firstLabel: String, style: ResponseStyle): String =
        if (tel(style)) "$name ki $count numbers unnayi. $firstLabel number ki call cheyyala?"
        else "$name has $count numbers. Should I call the $firstLabel one?"

    fun multipleContacts(name: String, count: Int, style: ResponseStyle): String =
        if (tel(style)) "$name ane peru tho $count contacts unnayi. Edi kavali?"
        else "There are $count contacts named $name. Which one?"

    // ---- Messaging ----------------------------------------------------------------------

    fun askMessageBody(name: String?, style: ResponseStyle): String = when {
        tel(style) && name != null -> "$name ki em message pampali?"
        tel(style) -> "Em message pampali?"
        name != null -> "What should I send $name?"
        else -> "What's the message?"
    }

    fun confirmMessage(name: String, body: String, channel: String?, style: ResponseStyle): String {
        val where = channel?.let { if (tel(style)) " $it lo" else " on $it" }.orEmpty()
        return if (tel(style)) "$name ki$where \"$body\" ani pampana?"
        else "Send \"$body\" to $name$where?"
    }

    /**
     * Android hands a prepared message to the messaging app; the user presses send.
     * Say that plainly rather than claiming it went out.
     */
    fun messageHandedOff(name: String, appLabel: String, style: ResponseStyle): String =
        if (tel(style)) "$appLabel lo $name ki message ready chesa. Nuvvu send press cheyyi."
        else "I've drafted the message to $name in $appLabel. Tap send to deliver it."

    fun messageSent(name: String, style: ResponseStyle): String =
        if (tel(style)) "$name ki pampesa${style.particle}." else "Sent to $name."

    // ---- Notifications --------------------------------------------------------------------

    fun noNotifications(style: ResponseStyle): String =
        if (tel(style)) "Kotha notifications emi ledu${style.particle}."
        else "Nothing new right now."

    fun notificationAccessMissing(style: ResponseStyle): String =
        if (tel(style)) "Notification access ivvaledu. Settings lo enable chesthe messages chadivi cheptha."
        else "I don't have notification access yet. Enable it in Settings and I'll read your messages out."

    // ---- Music -----------------------------------------------------------------------------

    fun playingQuery(query: String, provider: String?, style: ResponseStyle): String {
        val where = provider?.let { if (tel(style)) " $it lo" else " on $it" }.orEmpty()
        return if (tel(style)) "$query$where play chesthunna${style.particle}."
        else "Playing $query$where."
    }

    /**
     * Used when playback could not be started directly and Ani has only opened a search.
     * This distinction matters: claiming a song is playing when it is not is exactly the
     * kind of lie that makes an assistant untrustworthy.
     */
    fun openedMusicSearch(query: String, appLabel: String, style: ResponseStyle): String =
        if (tel(style)) "$appLabel lo \"$query\" search chesi icha. Play press cheyyi${style.particle}."
        else "I've opened a search for \"$query\" in $appLabel — tap play to start it."

    fun musicActionDone(action: String, style: ResponseStyle): String = if (tel(style)) {
        when (action) {
            "next" -> "Next song${style.particle}."
            "previous" -> "Mundu song${style.particle}."
            "pause" -> "Aapesa${style.particle}."
            "resume" -> "Malli pettesa${style.particle}."
            "stop" -> "Aapesa${style.particle}."
            "replay" -> "Malli pettesa${style.particle}."
            else -> acknowledged(style)
        }
    } else {
        when (action) {
            "next" -> "Next track."
            "previous" -> "Previous track."
            "pause" -> "Paused."
            "resume" -> "Resumed."
            "stop" -> "Stopped."
            "replay" -> "Playing it again."
            else -> acknowledged(style)
        }
    }

    fun nothingPlaying(style: ResponseStyle): String =
        if (tel(style)) "Ippudu emi play avvatledu${style.particle}." else "Nothing is playing right now."

    fun askWhatToPlay(style: ResponseStyle): String =
        if (tel(style)) "Em paata pettali?" else "What should I play?"

    // ---- Apps --------------------------------------------------------------------------------

    fun openedApp(appLabel: String, style: ResponseStyle): String =
        if (tel(style)) "$appLabel open chesa${style.particle}." else "Opened $appLabel."

    fun appNotInstalled(appLabel: String, style: ResponseStyle): String =
        if (tel(style)) "$appLabel install ayyi ledu${style.particle}."
        else "$appLabel isn't installed."

    fun appNotFound(name: String, style: ResponseStyle): String =
        if (tel(style)) "$name ane app dorakaledu${style.particle}."
        else "I couldn't find an app called $name."

    // ---- Alarms, timers, reminders ---------------------------------------------------------------

    fun alarmSet(spec: TimeSpec, style: ResponseStyle): String {
        val phrase = TimePhrasing.describe(spec, style)
        return if (tel(style)) "Sare${style.particle}, $phrase alarm petta." else "Alarm set for $phrase."
    }

    fun timerSet(spec: TimeSpec, style: ResponseStyle): String {
        val phrase = TimePhrasing.describe(spec, style)
        return if (tel(style)) "$phrase timer start chesa${style.particle}." else "Timer set for $phrase."
    }

    fun reminderSet(text: String, spec: TimeSpec, style: ResponseStyle): String {
        val phrase = TimePhrasing.describe(spec, style)
        return if (tel(style)) "Sare, $phrase \"$text\" ani gurthu chestha."
        else "I'll remind you to $text at $phrase."
    }

    /** The one question that prevents a 7pm alarm from becoming a 7am disaster. */
    fun askMorningOrEvening(hour12: Int, style: ResponseStyle): String =
        if (tel(style)) "Morning $hour12 aa, evening $hour12 aa?"
        else "$hour12 in the morning or the evening?"

    fun askWhen(style: ResponseStyle): String =
        if (tel(style)) "Eppudu?" else "When?"

    fun askWhatToRemind(style: ResponseStyle): String =
        if (tel(style)) "Enti gurthu cheyyali?" else "What should I remind you about?"

    // ---- Device -------------------------------------------------------------------------------

    fun batteryLevel(percent: Int, charging: Boolean, style: ResponseStyle): String = when {
        tel(style) && charging -> "$percent percent undi, charge avthundi${style.particle}."
        tel(style) -> "$percent percent undi${style.particle}."
        charging -> "$percent percent and charging."
        else -> "$percent percent."
    }

    fun flashlightOn(style: ResponseStyle): String =
        if (tel(style)) "Light on chesa${style.particle}." else "Flashlight on."

    fun flashlightOff(style: ResponseStyle): String =
        if (tel(style)) "Light off chesa${style.particle}." else "Flashlight off."

    fun noFlashlight(style: ResponseStyle): String =
        if (tel(style)) "Ee phone lo flashlight dorakaledu${style.particle}."
        else "I couldn't find a flashlight on this phone."

    fun volumeSet(percent: Int, style: ResponseStyle): String =
        if (tel(style)) "Volume $percent percent ki petta${style.particle}." else "Volume set to $percent percent."

    /**
     * Wi-Fi, Bluetooth, airplane mode and brightness cannot be changed by a normal app on
     * current Android. Ani opens the right screen and says so instead of pretending.
     */
    fun openedSettingsScreen(target: String, style: ResponseStyle): String =
        if (tel(style)) "$target settings open chesa${style.particle}. Akkada nunchi marchukovachu."
        else "I've opened $target settings — you can change it there."

    fun cannotToggleDirectly(target: String, style: ResponseStyle): String =
        if (tel(style)) "$target ni nenu direct ga marchalenu — Android alaa allow cheyyadu. Settings open chesa."
        else "Android doesn't let an app change $target directly, so I've opened the settings screen."

    fun dndNeedsPermission(style: ResponseStyle): String =
        if (tel(style)) "Do Not Disturb marchadaniki permission kavali. Settings lo ivvu, tarvata chestha."
        else "I need Do Not Disturb access for that. Grant it in Settings and I'll handle it."

    fun confirmDnd(turningOn: Boolean, style: ResponseStyle): String = when {
        tel(style) && turningOn -> "Do Not Disturb on cheyyala? Calls raavu."
        tel(style) -> "Do Not Disturb off cheyyala?"
        turningOn -> "Turn on Do Not Disturb? Calls will be silenced."
        else -> "Turn off Do Not Disturb?"
    }

    // ---- Information --------------------------------------------------------------------------

    fun currentTime(clock: String, style: ResponseStyle): String =
        if (tel(style)) "$clock ayyindi${style.particle}." else "It's $clock."

    fun currentDate(date: String, style: ResponseStyle): String =
        if (tel(style)) "Ivala $date${style.particle}." else "Today is $date."

    fun weatherUnavailable(style: ResponseStyle): String =
        if (tel(style)) "Weather teliyadaniki internet kavali${style.particle}."
        else "I need an internet connection for the weather."

    // ---- Memory and custom commands -------------------------------------------------------------

    fun rememberedFact(key: String, value: String, style: ResponseStyle): String =
        if (tel(style)) "Sare, $key ante $value ani gurthu pettukunna."
        else "Got it — $key means $value."

    fun confirmSaveCommand(phrase: String, style: ResponseStyle): String =
        if (tel(style)) "\"$phrase\" ni custom command ga save cheyyala?"
        else "Save \"$phrase\" as a custom command?"

    fun commandSaved(phrase: String, style: ResponseStyle): String =
        if (tel(style)) "\"$phrase\" save chesa${style.particle}." else "Saved \"$phrase\"."

    fun runningCommand(phrase: String, style: ResponseStyle): String =
        if (tel(style)) "$phrase chesthunna${style.particle}." else "Running $phrase."

    // ---- Errors ---------------------------------------------------------------------------------

    fun didNotCatch(style: ResponseStyle): String =
        if (tel(style)) "Sariga vinapadaledu${style.particle}, malli cheppu."
        else "I didn't catch that — say it again?"

    /**
     * For the second empty attempt in a row.
     *
     * Deliberately different from [didNotCatch]: hearing the same sentence twice makes an
     * assistant sound broken, and this one asks rather than apologises.
     */
    fun sayItAgain(style: ResponseStyle): String =
        if (tel(style)) "Inkosari cheppu${style.particle}, nenu vintunna."
        else "Once more — I'm listening."

    /**
     * When the microphone produced nothing because something else had it.
     *
     * Never phrased as "I didn't hear you". The user was not the problem, they were not
     * quiet, and saying it again will not help — so the message says what actually
     * happened instead of asking them to repeat themselves.
     */
    fun microphoneBusy(style: ResponseStyle): String =
        if (tel(style)) "Microphone inko app daggara undi${style.particle}. Nuvvu cheppindi naaku vinapadaledu."
        else "Something else has the microphone, so nothing reached me."

    fun didNotUnderstand(style: ResponseStyle): String =
        if (tel(style)) "Ardham kaaledu${style.particle}, malli cheppu."
        else "I didn't understand that."

    fun dontKnowHow(style: ResponseStyle): String =
        if (tel(style)) "Adi ela cheyyalo naaku inka nerpinchaledu. Custom command ga add cheyyachu."
        else "I haven't learned that one yet — you can add it as a custom command."

    fun noInternet(style: ResponseStyle): String =
        if (tel(style)) "Internet ledu${style.particle}. Basic phone commands matram chestha."
        else "No internet right now — I can still handle basic phone commands."

    fun networkTrouble(style: ResponseStyle): String =
        if (tel(style)) "Internet problem undi${style.particle}. Malli try chestha."
        else "Network trouble — I'll try again."

    fun permissionMissing(feature: String, style: ResponseStyle): String =
        if (tel(style)) "$feature ki permission ledu${style.particle}. Settings lo isthe chestha."
        else "I don't have permission for $feature. Grant it in Settings and I'll do it."

    fun cancelled(style: ResponseStyle): String =
        if (tel(style)) "Sare, vadileysa${style.particle}." else "Okay, cancelled."

    fun somethingWentWrong(style: ResponseStyle): String =
        if (tel(style)) "Emo tappu jarigindi${style.particle}. Malli try cheyyi."
        else "Something went wrong — try again."
}
