package com.ani.assistant.platform.contacts

import android.content.Context
import android.provider.ContactsContract
import com.ani.assistant.core.log.AniLog
import com.ani.nlu.text.Fuzzy
import com.ani.nlu.text.PhoneticKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One number on a contact card. */
data class ContactNumber(
    val number: String,
    /** "Mobile", "Home", "Work" — what Ani says when asking which one. */
    val label: String,
    val isPrimary: Boolean
)

data class ResolvedContact(
    val contactId: String,
    val displayName: String,
    val numbers: List<ContactNumber>,
    val isStarred: Boolean
) {
    val hasSingleNumber: Boolean get() = numbers.size == 1

    /** Mobile first, then the contact's own default, then whatever exists. */
    fun preferredNumber(): ContactNumber? =
        numbers.firstOrNull { it.label.equals("Mobile", ignoreCase = true) }
            ?: numbers.firstOrNull { it.isPrimary }
            ?: numbers.firstOrNull()
}

/** What a name lookup produced. */
sealed interface ContactLookup {
    data class Single(val contact: ResolvedContact) : ContactLookup
    data class Multiple(val candidates: List<ResolvedContact>) : ContactLookup
    data object NotFound : ContactLookup
    data object PermissionMissing : ContactLookup
}

/**
 * Turns a spoken name into a phone number.
 *
 * Matching happens in three passes, most confident first: exact display name, phonetic
 * match on any word of the name, then a tolerant edit-distance pass. The phonetic pass is
 * the one that matters here — a contact saved as "Amma ❤️" or "Rahul Kumar (College)"
 * has to be found from the single word the user actually said.
 *
 * Ambiguity is returned, never resolved silently. Calling the wrong person is the most
 * embarrassing thing this app can do, so "Rahul" matching two contacts produces
 * [ContactLookup.Multiple] and the assistant asks.
 */
class ContactResolver(private val context: Context) {

    /**
     * @param spokenName the name as heard, e.g. "amma" or "rahul"
     * @param hasPermission whether READ_CONTACTS is granted; passed in so this class
     *        stays free of permission logic and testable without a device
     */
    suspend fun resolve(spokenName: String, hasPermission: Boolean): ContactLookup =
        withContext(Dispatchers.IO) {
            if (!hasPermission) return@withContext ContactLookup.PermissionMissing
            if (spokenName.isBlank()) return@withContext ContactLookup.NotFound

            val candidates = try {
                loadCandidates(spokenName)
            } catch (error: SecurityException) {
                AniLog.w(TAG, "contacts read denied at query time")
                return@withContext ContactLookup.PermissionMissing
            } catch (error: Exception) {
                AniLog.e(TAG, "contact query failed", error)
                return@withContext ContactLookup.NotFound
            }

            when {
                candidates.isEmpty() -> ContactLookup.NotFound
                candidates.size == 1 -> ContactLookup.Single(candidates.first())
                else -> {
                    // A starred contact beats an unstarred one outright; people star the
                    // handful they call constantly, which is exactly this situation.
                    val starred = candidates.filter { it.isStarred }
                    if (starred.size == 1) {
                        ContactLookup.Single(starred.first())
                    } else {
                        ContactLookup.Multiple(candidates.take(MAX_CANDIDATES))
                    }
                }
            }
        }

    /** Favourites, for the quick-actions row on the home screen. */
    suspend fun favourites(hasPermission: Boolean, limit: Int = 6): List<ResolvedContact> =
        withContext(Dispatchers.IO) {
            if (!hasPermission) return@withContext emptyList()
            try {
                queryContacts(selection = "${ContactsContract.Contacts.STARRED} = 1")
                    .take(limit)
            } catch (error: Exception) {
                AniLog.e(TAG, "favourites query failed", error)
                emptyList()
            }
        }

    private fun loadCandidates(spokenName: String): List<ResolvedContact> {
        val spokenKey = PhoneticKey.of(spokenName)
        val spokenWords = spokenName.trim().split(' ').filter { it.isNotBlank() }

        // Let the provider do the cheap narrowing first.
        val prefiltered = queryContacts(
            selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
            selectionArgs = arrayOf("%${spokenWords.firstOrNull().orEmpty()}%")
        )

        val exact = prefiltered.filter { it.displayName.equals(spokenName, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact

        // Widen to every contact only when the cheap pass found nothing — a spoken name
        // often differs from the stored spelling in ways SQL LIKE cannot see.
        val pool = prefiltered.ifEmpty { queryContacts() }

        val phonetic = pool.filter { contact ->
            contact.displayName.split(' ', ',', '(', ')', '-')
                .filter { it.isNotBlank() }
                .any { PhoneticKey.of(it) == spokenKey }
        }
        if (phonetic.isNotEmpty()) return phonetic

        return pool.filter { contact ->
            contact.displayName.split(' ', ',', '(', ')', '-')
                .filter { it.isNotBlank() }
                .any { Fuzzy.matches(PhoneticKey.of(it), spokenKey) }
        }
    }

    private fun queryContacts(
        selection: String? = null,
        selectionArgs: Array<String>? = null
    ): List<ResolvedContact> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.STARRED
        )

        val byContact = LinkedHashMap<String, MutableList<ContactNumber>>()
        val names = HashMap<String, String>()
        val starred = HashMap<String, Boolean>()

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
            val labelIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
            val primaryIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.IS_PRIMARY)
            val starredIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.STARRED)

            while (cursor.moveToNext()) {
                val contactId = cursor.getString(idIndex) ?: continue
                val number = cursor.getString(numberIndex)?.takeIf { it.isNotBlank() } ?: continue
                val name = cursor.getString(nameIndex) ?: continue

                names[contactId] = name
                starred[contactId] = cursor.getInt(starredIndex) == 1

                val label = ContactsContract.CommonDataKinds.Phone
                    .getTypeLabel(context.resources, cursor.getInt(typeIndex), cursor.getString(labelIndex))
                    .toString()

                val numbers = byContact.getOrPut(contactId) { mutableListOf() }
                // The same number can appear twice when accounts are merged.
                if (numbers.none { it.number.digitsOnly() == number.digitsOnly() }) {
                    numbers += ContactNumber(
                        number = number,
                        label = label,
                        isPrimary = cursor.getInt(primaryIndex) == 1
                    )
                }
            }
        }

        return byContact.map { (contactId, numbers) ->
            ResolvedContact(
                contactId = contactId,
                displayName = names[contactId].orEmpty(),
                numbers = numbers,
                isStarred = starred[contactId] == true
            )
        }
    }

    private fun String.digitsOnly(): String = filter { it.isDigit() }.takeLast(10)

    private companion object {
        const val TAG = "AniContacts"
        const val MAX_CANDIDATES = 5
    }
}
