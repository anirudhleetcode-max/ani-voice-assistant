package com.ani.assistant.assistant.tools

import com.ani.assistant.assistant.AniTool
import com.ani.assistant.assistant.ToolContext
import com.ani.assistant.core.permission.AniPermission
import com.ani.assistant.core.log.AniLog
import com.ani.assistant.core.permission.PermissionManager
import com.ani.assistant.core.result.AniResult
import com.ani.assistant.data.memory.MemoryCategory
import com.ani.assistant.data.memory.MemoryRepository
import com.ani.assistant.platform.contacts.ContactLookup
import com.ani.assistant.platform.contacts.ContactResolver
import com.ani.assistant.platform.contacts.ResolvedContact
import com.ani.assistant.platform.share.CallOutcome
import com.ani.assistant.platform.share.CommunicationLauncher
import com.ani.assistant.platform.share.MessageOutcome
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.ParsedCommand
import com.ani.nlu.intent.SlotKey
import com.ani.nlu.response.Responses

/**
 * Places calls.
 *
 * The interesting work is resolution, not dialling. A spoken "Amma" has to become a phone
 * number through the user's own aliases first, then the contact list, and any ambiguity
 * has to come back as a question rather than a guess — ringing the wrong Rahul is the
 * kind of mistake that makes someone uninstall an assistant.
 *
 * CALL_PHONE is preferred but not required: without it Ani opens the dialler with the
 * number filled in and says so.
 */
class CallContactTool(
    private val contacts: ContactResolver,
    private val memory: MemoryRepository,
    private val launcher: CommunicationLauncher,
    private val permissions: PermissionManager
) : AniTool {

    override val id: String = "call_contact"
    override val handles: Set<IntentType> = setOf(IntentType.CALL_CONTACT)
    override val requiredPermissions: List<AniPermission> = listOf(AniPermission.CONTACTS)

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        val spokenName = command[SlotKey.CONTACT_NAME]
            ?: return AniResult.NeedsInput(
                Responses.whichContact(context.style),
                SlotKey.CONTACT_NAME.name
            )

        // The user's own alias wins: "Amma ante Lakshmi" means look for Lakshmi.
        val nameToFind = memory.resolveAlias(MemoryCategory.CONTACT_ALIAS, spokenName) ?: spokenName

        return when (val lookup = contacts.resolve(nameToFind, hasPermission = true)) {
            is ContactLookup.PermissionMissing -> AniResult.NeedsPermission(
                Responses.permissionMissing(AniPermission.CONTACTS.displayName, context.style),
                AniPermission.CONTACTS.storageKey
            )

            is ContactLookup.NotFound -> AniResult.Failure(
                Responses.contactNotFound(spokenName, context.style)
            )

            is ContactLookup.Multiple -> AniResult.NeedsInput(
                Responses.multipleContacts(spokenName, lookup.candidates.size, context.style),
                SlotKey.CONTACT_NAME.name
            )

            is ContactLookup.Single -> placeCall(lookup.contact, spokenName, command, context)
        }
    }

    private fun placeCall(
        contact: ResolvedContact,
        spokenName: String,
        command: ParsedCommand,
        context: ToolContext
    ): AniResult {
        val number = contact.preferredNumber()
            ?: return AniResult.Failure(Responses.contactNotFound(spokenName, context.style))

        // More than one number is a real fork in the road, so ask rather than assume —
        // but only once. Without the `confirmed` check this branch is re-entered after
        // the user says yes, asks the identical question, and loops forever.
        if (!contact.hasSingleNumber && !command.confirmed) {
            return AniResult.NeedsConfirmation(
                Responses.multipleNumbers(
                    name = contact.displayName,
                    count = contact.numbers.size,
                    firstLabel = number.label,
                    style = context.style
                )
            )
        }

        val canPlaceCalls = permissions.isGranted(AniPermission.PHONE)
        AniLog.i(
            TAG,
            "ACTION CALL_CONTACT execution started",
            "contact" to AniLog.redact(contact.displayName),
            "numberResolved" to true,
            "confirmed" to command.confirmed,
            "canPlaceCalls" to canPlaceCalls
        )
        return when (val outcome = launcher.call(number.number, canPlaceCalls, contact.displayName)) {
            CallOutcome.Placing -> AniResult.Success(
                Responses.callingContact(contact.displayName, context.style)
            )

            CallOutcome.DiallerOpened -> AniResult.Limitation(
                spokenResponse = Responses.callingContact(contact.displayName, context.style) +
                    " " + dialHint(context),
                fallbackTaken = "opened the dialler"
            )

            CallOutcome.NoDialler -> AniResult.Failure(
                Responses.somethingWentWrong(context.style)
            )

            // Android refused a background activity start. The call is one tap away
            // behind a notification, and saying so is the only honest option — this is
            // exactly the case that used to be reported as "calling".
            is CallOutcome.Deferred -> AniResult.Limitation(
                spokenResponse = if (context.style.speaksTelugu) {
                    "${contact.displayName} ki call ready chesa${context.style.particle}. " +
                        "Notification touch chesthe call avtundi."
                } else {
                    "I've queued the call to ${contact.displayName} — tap the notification to connect."
                },
                fallbackTaken = "deferred to a notification"
            )

            is CallOutcome.Failed -> AniResult.Failure(Responses.somethingWentWrong(context.style))
        }
    }

    private companion object {
        const val TAG = "AniCallTool"
    }

    private fun dialHint(context: ToolContext) = if (context.style.speaksTelugu) {
        "Call button press cheyyi."
    } else {
        "Press call to connect."
    }
}

/**
 * Composes messages.
 *
 * Ani never sends a message on the user's behalf without them pressing send. That is not
 * a shortcut — see [CommunicationLauncher] for why every alternative is either a Play
 * policy violation or a technique this project will not implement. What Ani does do is
 * remove all the typing: the right app, the right chat, the right words, ready to go.
 */
class SendMessageTool(
    private val contacts: ContactResolver,
    private val memory: MemoryRepository,
    private val launcher: CommunicationLauncher,
    private val permissions: PermissionManager
) : AniTool {

    override val id: String = "send_message"
    override val handles: Set<IntentType> = setOf(IntentType.SEND_MESSAGE)

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        val spokenName = command[SlotKey.CONTACT_NAME]
        val body = command[SlotKey.MESSAGE_BODY]
            ?: return AniResult.NeedsInput(
                Responses.askMessageBody(spokenName, context.style),
                SlotKey.MESSAGE_BODY.name
            )

        // No channel named means "whatever you normally use" — the launcher falls back to
        // SMS when a number is known, and the share sheet when it is not.
        val channel = command[SlotKey.MESSAGE_CHANNEL]

        // A message with no recipient still has somewhere useful to go: the share sheet.
        if (spokenName == null) {
            return when (val outcome = launcher.composeMessage(null, body, channel)) {
                is MessageOutcome.Composed -> AniResult.Limitation(
                    Responses.messageHandedOff("", outcome.appLabel, context.style).trim(),
                    fallbackTaken = "opened the share sheet"
                )
                is MessageOutcome.AppNotInstalled ->
                    AniResult.Failure(Responses.appNotInstalled(outcome.appLabel, context.style))
                MessageOutcome.Failed -> AniResult.Failure(Responses.somethingWentWrong(context.style))
            }
        }

        if (!permissions.isGranted(AniPermission.CONTACTS)) {
            return AniResult.NeedsPermission(
                Responses.permissionMissing(AniPermission.CONTACTS.displayName, context.style),
                AniPermission.CONTACTS.storageKey
            )
        }

        val nameToFind = memory.resolveAlias(MemoryCategory.CONTACT_ALIAS, spokenName) ?: spokenName
        val contact = when (val lookup = contacts.resolve(nameToFind, hasPermission = true)) {
            is ContactLookup.Single -> lookup.contact
            is ContactLookup.Multiple -> return AniResult.NeedsInput(
                Responses.multipleContacts(spokenName, lookup.candidates.size, context.style),
                SlotKey.CONTACT_NAME.name
            )
            ContactLookup.NotFound -> return AniResult.Failure(
                Responses.contactNotFound(spokenName, context.style)
            )
            ContactLookup.PermissionMissing -> return AniResult.NeedsPermission(
                Responses.permissionMissing(AniPermission.CONTACTS.displayName, context.style),
                AniPermission.CONTACTS.storageKey
            )
        }

        val number = contact.preferredNumber()?.number
        return when (val outcome = launcher.composeMessage(number, body, channel)) {
            is MessageOutcome.Composed -> AniResult.Limitation(
                spokenResponse = Responses.messageHandedOff(
                    contact.displayName,
                    outcome.appLabel,
                    context.style
                ),
                fallbackTaken = "composed the message"
            )

            is MessageOutcome.AppNotInstalled ->
                AniResult.Failure(Responses.appNotInstalled(outcome.appLabel, context.style))

            MessageOutcome.Failed -> AniResult.Failure(Responses.somethingWentWrong(context.style))
        }
    }
}
