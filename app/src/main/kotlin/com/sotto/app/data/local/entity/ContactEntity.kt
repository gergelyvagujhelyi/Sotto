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
            louderVoice == other.louderVoice
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
        return result
    }
}
