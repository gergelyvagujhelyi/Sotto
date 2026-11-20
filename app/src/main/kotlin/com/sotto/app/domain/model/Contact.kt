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
            profileName == other.profileName
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
        return result
    }
}
