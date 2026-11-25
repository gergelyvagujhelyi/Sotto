package com.sotto.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val publicIdentityKey: ByteArray,
    val publicSigningKey: ByteArray,
    val pqcSigningKey: ByteArray = ByteArray(0),
    val verified: Boolean = false,
    val pqcEstablished: Boolean = false,
    val signatureRequired: Boolean = true,
    /** Hex SHA-256 fingerprint over all public keys (see KeyFingerprint). Null for
     *  contacts saved before fingerprints existed; backfilled on next key check. */
    val keyFingerprint: String? = null,
    /** Name the user gave this contact on this phone; wins over [profileName]. */
    val nickname: String? = null,
    /** Name the contact chose and sent end-to-end encrypted. */
    val profileName: String? = null,
    /** Louder was on in the last call with this contact: calls with them start with it on. */
    val louderVoice: Boolean = false,
    /**
     * The user checked this contact's keys face to face: a QR code or NFC tap from their
     * phone matched [keyFingerprint] (InPersonVerifier). Kept through a rotation their old
     * keys endorsed; cleared when keys are replaced any other way.
     */
    val verifiedInPerson: Boolean = false,
    /** The user was told once, before their first message, that this contact isn't verified in person. */
    val inPersonWarned: Boolean = false,
    /**
     * The user compared a code with this contact in a Sotto call and it matched (CallVerification).
     * Weaker than [verifiedInPerson]: the chat says "Verified by call only". Kept and cleared
     * like [verifiedInPerson].
     */
    val verifiedByCall: Boolean = false,
    /**
     * Not a contact of the user: a group member's keys, vouched for by the group's admin
     * (ADR-32). Pinned like a contact's so their messages can be checked and answered in the
     * group, but never shown as a contact, never offered a chat or a direct call. Cleared
     * when the user adds them, or accepts a message request from them.
     */
    val groupOnly: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactEntity) return false
        return userId == other.userId &&
            displayName == other.displayName &&
            publicIdentityKey.contentEquals(other.publicIdentityKey) &&
            publicSigningKey.contentEquals(other.publicSigningKey) &&
            pqcSigningKey.contentEquals(other.pqcSigningKey) &&
            verified == other.verified &&
            pqcEstablished == other.pqcEstablished &&
            signatureRequired == other.signatureRequired &&
            keyFingerprint == other.keyFingerprint &&
            nickname == other.nickname &&
            profileName == other.profileName &&
            louderVoice == other.louderVoice &&
            verifiedInPerson == other.verifiedInPerson &&
            inPersonWarned == other.inPersonWarned &&
            verifiedByCall == other.verifiedByCall &&
            groupOnly == other.groupOnly
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + publicIdentityKey.contentHashCode()
        result = 31 * result + publicSigningKey.contentHashCode()
        result = 31 * result + pqcSigningKey.contentHashCode()
        result = 31 * result + verified.hashCode()
        result = 31 * result + pqcEstablished.hashCode()
        result = 31 * result + signatureRequired.hashCode()
        result = 31 * result + (keyFingerprint?.hashCode() ?: 0)
        result = 31 * result + (nickname?.hashCode() ?: 0)
        result = 31 * result + (profileName?.hashCode() ?: 0)
        result = 31 * result + louderVoice.hashCode()
        result = 31 * result + verifiedInPerson.hashCode()
        result = 31 * result + inPersonWarned.hashCode()
        result = 31 * result + verifiedByCall.hashCode()
        result = 31 * result + groupOnly.hashCode()
        return result
    }
}
