package com.sotto.app.domain.model

data class Contact(
    val userId: String,
    val displayName: String,
    val publicIdentityKey: ByteArray,
    val publicSigningKey: ByteArray,
    val pqcSigningKey: ByteArray = ByteArray(0),
    val verified: Boolean = false,
    /** Name the user gave this contact; [displayName] already prefers it. */
    val nickname: String? = null,
    /** Name the contact chose and sent end-to-end encrypted. */
    val profileName: String? = null,
    /** Checked face to face, by QR code or NFC from their phone (see ContactEntity). */
    val verifiedInPerson: Boolean = false,
    /** Already told once that they aren't verified in person, before a first message. */
    val inPersonWarned: Boolean = false,
    /** A code compared in a Sotto call matched: weaker than [verifiedInPerson]. */
    val verifiedByCall: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Contact) return false
        return userId == other.userId &&
            displayName == other.displayName &&
            publicIdentityKey.contentEquals(other.publicIdentityKey) &&
            publicSigningKey.contentEquals(other.publicSigningKey) &&
            pqcSigningKey.contentEquals(other.pqcSigningKey) &&
            verified == other.verified &&
            nickname == other.nickname &&
            profileName == other.profileName &&
            verifiedInPerson == other.verifiedInPerson &&
            inPersonWarned == other.inPersonWarned &&
            verifiedByCall == other.verifiedByCall
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + publicIdentityKey.contentHashCode()
        result = 31 * result + publicSigningKey.contentHashCode()
        result = 31 * result + pqcSigningKey.contentHashCode()
        result = 31 * result + verified.hashCode()
        result = 31 * result + (nickname?.hashCode() ?: 0)
        result = 31 * result + (profileName?.hashCode() ?: 0)
        result = 31 * result + verifiedInPerson.hashCode()
        result = 31 * result + inPersonWarned.hashCode()
        result = 31 * result + verifiedByCall.hashCode()
        return result
    }
}

/** How far a contact has been checked: shown in the chat list, New chat and the chat header. */
enum class ContactTrust { IN_PERSON, BY_PHONE, NONE }

val Contact.trust: ContactTrust
    get() = when {
        verifiedInPerson -> ContactTrust.IN_PERSON
        verifiedByCall -> ContactTrust.BY_PHONE
        else -> ContactTrust.NONE
    }
