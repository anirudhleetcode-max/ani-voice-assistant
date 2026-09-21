package com.ani.nlu.lexicon

/**
 * A meaning class that a spoken word can belong to.
 *
 * Ani never matches raw strings against intents. Every word is first mapped to zero or
 * more [SemanticTag]s, and the intent rules are written against tags. That is what makes
 * "call chey", "phone cheyyi", "call pettu" and "కాల్ చెయ్" all behave identically without
 * writing three hundred string literals per intent.
 *
 * Telugu leans heavily on *light verbs* — "chey" (do) and "pettu" (put) carry almost no
 * meaning by themselves and take it from the noun beside them ("alarm pettu", "song
 * pettu", "DND pettu"). They therefore get their own tags ([LIGHT_DO], [LIGHT_PUT])
 * rather than being folded into a single generic "verb" bucket.
 */
enum class SemanticTag {

    // ---- Light verbs -------------------------------------------------------------
    LIGHT_DO,       // chey, cheyyi, chesey, do
    LIGHT_PUT,      // pettu, petti, pettandi, set

    // ---- Contentful verbs --------------------------------------------------------
    V_CALL,         // call, phone, ring, piluvu, kottu
    V_SEND,         // pampu, send, forward
    V_PLAY,         // play, vinipinchu
    V_OPEN,         // open, teruvu, launch, start
    V_CLOSE,        // close, mooyi, band
    V_STOP,         // stop, aapu, aagu
    V_PAUSE,        // pause
    V_RESUME,       // resume, continue, konasaginchu
    V_NEXT,         // next, tarvata, skip
    V_PREVIOUS,     // previous, back, venakki
    V_READ,         // read, chaduvu
    V_TELL,         // cheppu, tell, say
    V_WAKE,         // lepu, lepali, wake
    V_REMIND,       // gurthu chey, remind
    V_INCREASE,     // penchu, increase, up
    V_DECREASE,     // taggu, decrease, down
    V_TURN_ON,      // on, veliginchu, enable
    V_TURN_OFF,     // off, arpu, disable
    V_SEARCH,       // search, vetuku, find
    V_SHOW,         // chupinchu, show, choodu
    V_NAVIGATE,     // navigate, route, daari
    V_REPEAT,       // malli, again, repeat
    V_TEACH,        // nerpinchu, teach, save as command
    V_REMEMBER,     // gurthu pettuko, remember, note
    V_DELETE,       // delete, tholagichu, remove
    V_SHARE,        // share

    // ---- Domain nouns ------------------------------------------------------------
    N_MUSIC,        // song, paata, music, track, album, playlist
    N_ARTIST,       // singer, artist, gaayakudu
    N_MESSAGE,      // message, msg, sms, text
    N_NOTIFICATION, // notification, alert
    N_CALL,         // "call" used as a noun (missed calls)
    N_MISSED,       // missed, miss
    N_ALARM,        // alarm
    N_TIMER,        // timer
    N_REMINDER,     // reminder, gurthu
    N_BATTERY,      // battery, charge
    N_FLASHLIGHT,   // flashlight, torch
    N_WIFI,
    N_BLUETOOTH,
    N_VOLUME,
    N_BRIGHTNESS,
    N_DND,          // do not disturb, silent
    N_AIRPLANE,
    N_TIME,         // time, samayam
    N_DATE,         // date, tedi
    N_WEATHER,      // weather, vaatavaranam
    N_SETTINGS,     // settings
    N_STORAGE,
    N_NETWORK,
    N_LOCATION,
    N_MAPS,
    N_APP,          // the literal word "app"
    N_PHONE,        // phone (device)
    N_KINSHIP,      // amma, nanna, akka, anna — strong hint that a contact follows

    // ---- Function words ----------------------------------------------------------
    F_WAKE,         // rey, ani, orey — default wake tokens
    F_FILLER,       // ra, oi, abba, please
    F_AFFIRM,       // avunu, sare, ok, yes
    F_DENY,         // kaadu, vaddu, no, cancel
    F_QUESTION,     // enti, em, entha, ela, ekkada, eppudu, enduku
    F_QUOTATIVE,    // ani — marks the end of quoted message content
    F_AGAIN,        // malli
    F_SELF,         // nenu, naaku, nannu, na, me, my
    F_NEGATION,     // ledu, kaadu
    F_ALL,          // anni, all
    F_NUMBER_WORD,  // okati, rendu, one, two ...

    // ---- Time words --------------------------------------------------------------
    T_TODAY,
    T_TOMORROW,
    T_YESTERDAY,
    T_NOW,
    T_MORNING,
    T_AFTERNOON,
    T_EVENING,
    T_NIGHT,
    T_HOUR_UNIT,    // ganta, gantalu, hour
    T_MINUTE_UNIT,  // nimisham, minutes
    T_SECOND_UNIT,  // sekanlu, seconds
    T_DAY_UNIT,     // roju, day
    T_AFTER,        // tarvata, after, lo (in N minutes)
    T_WEEKDAY;      // monday ... somavaram ...
}
