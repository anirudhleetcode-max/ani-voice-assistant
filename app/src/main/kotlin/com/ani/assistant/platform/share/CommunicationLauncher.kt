package com.ani.assistant.platform.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ani.assistant.core.log.AniLog
import java.net.URLEncoder

/** What happened when Ani tried to place a call. */
sealed interface CallOutcome {
    /** The call is being placed. Requires CALL_PHONE. */
    data object Placing : CallOutcome

    /** The dialler is open with the number filled in; the user presses the button. */
    data object DiallerOpened : CallOutcome

    data object NoDialler : CallOutcome
    data object Failed : CallOutcome
}

/** What happened when Ani tried to send a message. */
sealed interface MessageOutcome {
    /**
     * The message is open in the chosen app, composed and ready. The user presses send.
     *
     * This is the *only* success case for WhatsApp, and deliberately so — see the class
     * documentation.
     */
    data class Composed(val appLabel: String) : MessageOutcome

    data class AppNotInstalled(val appLabel: String) : MessageOutcome
    data object Failed : MessageOutcome
}

/**
 * Calls, messages, maps and sharing.
 *
 * **On sending messages without the user pressing send.** Android has no API for it, and
 * that is a deliberate platform decision rather than an oversight. SMS can technically be
 * sent silently with `SmsManager`, but doing so requires the SEND_SMS permission, which
 * Play restricts to apps whose core function is SMS and which would get this app
 * rejected. WhatsApp exposes no send API at all; the ways around that — accessibility
 * automation driving its UI, or reading its database — are exactly the techniques this
 * project refuses to implement.
 *
 * So Ani composes the message, hands it to the app, and says "message ready chesa, send
 * press cheyyi". One tap, and nothing done behind the user's back.
 */
class CommunicationLauncher(private val context: Context) {

    // ---- Calling -----------------------------------------------------------------------

    /**
     * @param canPlaceCalls whether CALL_PHONE is granted. When false this opens the
     *        dialler instead, which always works and costs one tap.
     */
    fun call(number: String, canPlaceCalls: Boolean): CallOutcome {
        val uri = Uri.fromParts("tel", number, null)

        if (canPlaceCalls) {
            val callIntent = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(callIntent)
                return CallOutcome.Placing
            } catch (error: SecurityException) {
                // Permission revoked between the check and the call.
                AniLog.w(TAG, "CALL_PHONE refused at call time, falling back to dialler")
            } catch (error: ActivityNotFoundException) {
                AniLog.w(TAG, "no activity handles ACTION_CALL")
            }
        }

        val dialIntent = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(dialIntent)
            CallOutcome.DiallerOpened
        } catch (error: ActivityNotFoundException) {
            CallOutcome.NoDialler
        } catch (error: Exception) {
            AniLog.e(TAG, "could not open dialler", error)
            CallOutcome.Failed
        }
    }

    // ---- Messaging ---------------------------------------------------------------------

    /**
     * Opens a chat with [number] in [channel], pre-filled with [body].
     *
     * @param channel "whatsapp", "sms", "telegram" or null to let the user choose
     */
    fun composeMessage(number: String?, body: String, channel: String?): MessageOutcome =
        when (channel) {
            "whatsapp" -> composeWhatsApp(number, body)
            "sms" -> composeSms(number, body)
            "telegram" -> composeViaShareSheet(body, "org.telegram.messenger", "Telegram")
            null -> if (number != null) composeSms(number, body) else composeViaShareSheet(body, null, "your apps")
            else -> composeViaShareSheet(body, null, "your apps")
        }

    /**
     * WhatsApp's documented `wa.me` deep link. It opens the chat with the text in the
     * input box; WhatsApp does not expose any way to send it.
     */
    private fun composeWhatsApp(number: String?, body: String): MessageOutcome {
        val encoded = URLEncoder.encode(body, "UTF-8")
        val digits = number?.filter { it.isDigit() || it == '+' }?.removePrefix("+")

        val candidates = buildList {
            if (!digits.isNullOrBlank()) {
                add(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=$encoded")))
            }
            add(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, body)
                    .setPackage(WHATSAPP_PACKAGE)
            )
        }

        for (intent in candidates) {
            val launchable = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchable.resolveActivity(context.packageManager) == null) continue
            return try {
                context.startActivity(launchable)
                MessageOutcome.Composed("WhatsApp")
            } catch (error: Exception) {
                continue
            }
        }
        return MessageOutcome.AppNotInstalled("WhatsApp")
    }

    private fun composeSms(number: String?, body: String): MessageOutcome {
        val uri = if (number.isNullOrBlank()) {
            Uri.parse("smsto:")
        } else {
            Uri.parse("smsto:${number.filter { it.isDigit() || it == '+' }}")
        }
        val intent = Intent(Intent.ACTION_SENDTO, uri)
            .putExtra("sms_body", body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (intent.resolveActivity(context.packageManager) == null) {
            return MessageOutcome.AppNotInstalled("Messages")
        }
        return try {
            context.startActivity(intent)
            MessageOutcome.Composed("Messages")
        } catch (error: Exception) {
            AniLog.e(TAG, "could not open messaging app", error)
            MessageOutcome.Failed
        }
    }

    private fun composeViaShareSheet(body: String, packageName: String?, label: String): MessageOutcome {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, body)
            .apply { packageName?.let { setPackage(it) } }

        val chooser = if (packageName == null) {
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(chooser)
            MessageOutcome.Composed(label)
        } catch (error: ActivityNotFoundException) {
            MessageOutcome.AppNotInstalled(label)
        } catch (error: Exception) {
            AniLog.e(TAG, "share sheet failed", error)
            MessageOutcome.Failed
        }
    }

    // ---- Maps and sharing ---------------------------------------------------------------

    /** Opens navigation to [destination]. Falls back to a plain map search. */
    fun navigateTo(destination: String): Boolean {
        val encoded = Uri.encode(destination)
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded"))
                .setPackage(MAPS_PACKAGE),
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")),
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/search/?api=1&query=$encoded")
            )
        )
        for (intent in candidates) {
            val launchable = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchable.resolveActivity(context.packageManager) == null) continue
            return try {
                context.startActivity(launchable)
                true
            } catch (error: Exception) {
                continue
            }
        }
        return false
    }

    /**
     * Shares a maps link for the given coordinates through the system share sheet.
     *
     * The share sheet is used on purpose: Ani never picks who receives the user's
     * location, and never sends it anywhere by itself.
     */
    fun shareLocation(latitude: Double, longitude: Double): Boolean {
        val link = "https://maps.google.com/?q=$latitude,$longitude"
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, link)
        return try {
            context.startActivity(
                Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (error: Exception) {
            AniLog.e(TAG, "could not share location", error)
            false
        }
    }

    private companion object {
        const val TAG = "AniComms"
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }
}
