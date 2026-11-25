package com.sotto.app.group

import com.sotto.app.crypto.lengthPrefixed
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * A group's members and settings at one epoch, signed by the admin who issued it (ADR-32).
 *
 * Only the admin changes a group. Each change is a new roster with a higher [epoch], signed
 * (Ed25519, plus ML-DSA-65 when the admin has a post-quantum key) over [canonical], and sent
 * to every member pairwise, inside their ordinary end-to-end encrypted messages. A phone keeps
 * the newest roster it accepted and refuses older ones.
 *
 * Members who aren't each other's contacts are vouched for by the admin: [RosterMember.fingerprint]
 * is the full 256-bit key fingerprint the admin has pinned for them, and a member's phone
 * uses keys for them only when they match it.
 */
@Serializable
data class GroupRoster(
    @SerialName("g") val groupId: String,
    @SerialName("e") val epoch: Long,
    /** Who issued (and signed) this roster: the admin up to this epoch. */
    @SerialName("by") val issuer: String,
    /** The admin from this epoch on; differs from [issuer] when the admin hands over. */
    @SerialName("a") val admin: String,
    @SerialName("t") val title: String,
    /** Disappearing messages in the group (0: off), set by the admin. */
    @SerialName("tm") val timerMs: Long,
    /** The admin deleted the group: nothing more is sent in it. */
    @SerialName("del") val deleted: Boolean = false,
    /** Sorted by [RosterMember.userId], each once. */
    @SerialName("m") val members: List<RosterMember>,
    /** The latest admin handovers, oldest first, so a phone that missed some can follow them. */
    @SerialName("x") val transfers: List<AdminTransfer> = emptyList(),
    @SerialName("sig") val signature: String = "",
    @SerialName("pq") val pqcSignature: String = "",
) {
    fun member(userId: String): RosterMember? = members.firstOrNull { it.userId == userId }

    fun isActive(userId: String): Boolean = !deleted && member(userId)?.status == RosterMember.ACTIVE

    val activeMembers: List<RosterMember> get() = if (deleted) emptyList() else members.filter { it.status == RosterMember.ACTIVE }

    /** The bytes the issuer signs: every field but the signatures and the transfers (which carry their own). */
    fun canonical(): ByteArray {
        var out = lengthPrefixed(ROSTER_DOMAIN) +
            text(groupId) + long(epoch) + text(issuer) + text(admin) + text(title) +
            long(timerMs) + byteArrayOf(if (deleted) 1 else 0) +
            ByteBuffer.allocate(4).putInt(members.size).array()
        for (m in members) {
            out += text(m.userId) + text(m.fingerprint) + text(m.name) + text(m.status) + text(m.trust)
        }
        return out
    }

    /** A short digest of [canonical], to tell two rosters of one epoch apart. */
    fun digest(): String =
        MessageDigest.getInstance("SHA-256").digest(canonical()).joinToString("") { "%02x".format(it) }.take(16)

    fun unsigned(): GroupRoster = copy(signature = "", pqcSignature = "")

    companion object {
        val ROSTER_DOMAIN = "Sotto-group-roster-v1".toByteArray(Charsets.UTF_8)
        val TRANSFER_DOMAIN = "Sotto-group-admin-v1".toByteArray(Charsets.UTF_8)
        const val MAX_MEMBERS = 32
        const val MAX_TITLE = 64
        const val MAX_NAME = 40
        /** Handovers a roster carries; a phone that missed more than this many can't follow. */
        const val MAX_TRANSFERS = 8
        /** The longest disappearing-messages timer a roster may set (30 days, the longest offered). */
        const val MAX_TIMER_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_EPOCH = 1L shl 53

        /** What a handover signs: the group, its epoch, both admins, and the keys the old one vouches for in the new one. */
        fun transferPayload(groupId: String, epoch: Long, from: String, to: String, toFingerprint: String): ByteArray =
            lengthPrefixed(TRANSFER_DOMAIN) + text(groupId) + long(epoch) + text(from) + text(to) + text(toFingerprint)

        internal fun text(s: String) = lengthPrefixed(s.toByteArray(Charsets.UTF_8))
        internal fun long(v: Long) = ByteBuffer.allocate(8).putLong(v).array()
    }
}

@Serializable
data class RosterMember(
    @SerialName("u") val userId: String,
    /** The full key fingerprint (64 hex) the issuer has pinned for this member. */
    @SerialName("f") val fingerprint: String,
    /** The name the member chose (from their acceptance), or empty. */
    @SerialName("n") val name: String,
    /** [ACTIVE] or [INVITED]. */
    @SerialName("st") val status: String,
    /** How the issuer verified this member: [TRUST_IN_PERSON], [TRUST_BY_PHONE], [TRUST_SHARE_CODE] or [TRUST_NONE]. */
    @SerialName("tr") val trust: String,
) {
    companion object {
        const val ACTIVE = "a"
        const val INVITED = "i"
        const val TRUST_IN_PERSON = "p"
        const val TRUST_BY_PHONE = "c"
        const val TRUST_SHARE_CODE = "s"
        const val TRUST_NONE = "n"
        val TRUSTS = setOf(TRUST_IN_PERSON, TRUST_BY_PHONE, TRUST_SHARE_CODE, TRUST_NONE)
    }
}

/**
 * "At [epoch], admin [from] handed the group to [to], whose keys are [toFingerprint]", signed by
 * [from]. The fingerprint lets a phone that never had [to]'s keys pin the right ones.
 */
@Serializable
data class AdminTransfer(
    @SerialName("e") val epoch: Long,
    @SerialName("f") val from: String,
    @SerialName("t") val to: String,
    @SerialName("tf") val toFingerprint: String,
    @SerialName("sig") val signature: String,
    @SerialName("pq") val pqcSignature: String = "",
)

/** An account's public signing keys, as this phone has them pinned. */
class SigningKeys(val ed25519: ByteArray, val mlDsa: ByteArray)

/** Signs as the local account and checks others' signatures (Ed25519 and ML-DSA-65). */
interface GroupSignatures {
    /** Ed25519 and ML-DSA-65 signatures (base64) over [data]; the second is "" without a post-quantum key. */
    fun sign(data: ByteArray): Pair<String, String>

    /** Both signatures check out against [keys]; ML-DSA is required whenever [keys] has a post-quantum key. */
    fun verify(data: ByteArray, signature: String, pqcSignature: String, keys: SigningKeys): Boolean
}

/** Checks rosters: their form ([validate]) and whether a phone may take one ([verifyUpdate]). */
object RosterRules {

    private val GROUP_ID = Regex("^[0-9a-f]{32}$")
    private val USER_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val FINGERPRINT = Regex("^[0-9a-f]{64}$")

    fun isGroupId(s: String) = GROUP_ID.matches(s)
    fun isUserId(s: String) = USER_ID.matches(s)
    fun isFingerprint(s: String) = FINGERPRINT.matches(s)

    /** Null when [roster] is well formed, else why not. */
    fun validate(roster: GroupRoster): String? {
        if (!isGroupId(roster.groupId)) return "group id"
        if (roster.epoch !in 1..GroupRoster.MAX_EPOCH) return "epoch"
        if (!isUserId(roster.issuer) || !isUserId(roster.admin)) return "admin"
        if (roster.title.isBlank() || roster.title.length > GroupRoster.MAX_TITLE || roster.title.any { it.isISOControl() }) return "title"
        if (roster.timerMs !in 0..GroupRoster.MAX_TIMER_MS) return "timer"
        if (roster.members.isEmpty() || roster.members.size > GroupRoster.MAX_MEMBERS) return "member count"
        var previous: String? = null
        for (m in roster.members) {
            if (!isUserId(m.userId) || !isFingerprint(m.fingerprint)) return "member"
            if (previous != null && m.userId <= previous) return "member order"
            previous = m.userId
            if (m.name.length > GroupRoster.MAX_NAME || m.name.any { it.isISOControl() }) return "member name"
            if (m.status != RosterMember.ACTIVE && m.status != RosterMember.INVITED) return "member status"
            if (m.trust !in RosterMember.TRUSTS) return "member trust"
        }
        if (!roster.deleted && roster.member(roster.admin)?.status != RosterMember.ACTIVE) return "admin not an active member"
        if (roster.transfers.size > GroupRoster.MAX_TRANSFERS) return "transfers"
        var lastEpoch = 0L
        for (t in roster.transfers) {
            if (t.epoch <= lastEpoch || t.epoch > roster.epoch) return "transfer order"
            if (!isUserId(t.from) || !isUserId(t.to) || t.from == t.to || !isFingerprint(t.toFingerprint)) return "transfer"
            lastEpoch = t.epoch
        }
        if (roster.signature.isEmpty()) return "unsigned"
        return null
    }

    sealed interface Verdict {
        data object Accept : Verdict
        /** Not newer than the one held: a duplicate or an old one, ignored. */
        data object Stale : Verdict
        /**
         * Signing keys of [userId] are needed and not on this phone (yet). [fingerprint] is the one
         * a source already verified vouches for (the roster held, or a checked handover), or null
         * when there's none: then the keys can't be taken from anywhere.
         */
        data class NeedKeys(val userId: String, val fingerprint: String? = null) : Verdict
        data class Reject(val reason: String) : Verdict
    }

    /**
     * Whether a phone holding [stored] (null: none yet, an invitation) may take [new], which
     * came from [sender] (the authenticated sender of its envelope). [keys] gives the signing
     * keys this phone has pinned for an account.
     *
     * - An invitation must be issued and signed by its sender, who is its admin.
     * - An update must have a higher epoch, be issued by the admin the phone holds, or one
     *   reached from it through handovers each signed by the admin before, and be sent by its issuer.
     * - A roster that names a new admin carries the handover for its own epoch, signed by its issuer.
     */
    fun verifyUpdate(
        new: GroupRoster,
        stored: GroupRoster?,
        sender: String,
        keys: (String) -> SigningKeys?,
        signatures: GroupSignatures,
    ): Verdict {
        validate(new)?.let { return Verdict.Reject(it) }
        if (new.issuer != sender) return Verdict.Reject("not sent by its issuer")
        // Fingerprints vouched for by something already checked: the roster held, then each
        // handover as it checks out. Never the new roster's own (it isn't checked yet).
        val vouched = stored?.members?.associate { it.userId to it.fingerprint }.orEmpty().toMutableMap()
        if (stored == null) {
            if (new.admin != sender) return Verdict.Reject("invitation not from its admin")
        } else {
            if (new.groupId != stored.groupId) return Verdict.Reject("other group")
            if (new.epoch <= stored.epoch) return Verdict.Stale
            var current = stored.admin
            for (t in new.transfers) {
                if (t.epoch <= stored.epoch || t.epoch >= new.epoch) continue
                if (t.from != current) return Verdict.Reject("handover from someone not admin")
                val fromKeys = keys(t.from) ?: return Verdict.NeedKeys(t.from, vouched[t.from])
                if (!signatures.verify(GroupRoster.transferPayload(new.groupId, t.epoch, t.from, t.to, t.toFingerprint), t.signature, t.pqcSignature, fromKeys)) {
                    return Verdict.Reject("handover signature")
                }
                vouched[t.to] = t.toFingerprint
                current = t.to
            }
            if (new.issuer != current) return Verdict.Reject("issuer is not the admin")
        }
        if (new.admin != new.issuer) {
            val own = new.transfers.lastOrNull { it.epoch == new.epoch }
                ?: return Verdict.Reject("new admin without a handover")
            if (own.from != new.issuer || own.to != new.admin) return Verdict.Reject("handover doesn't match")
            if (new.member(own.to)?.fingerprint != own.toFingerprint) return Verdict.Reject("handover names other keys")
            val issuerKeys = keys(new.issuer) ?: return Verdict.NeedKeys(new.issuer, vouched[new.issuer])
            if (!signatures.verify(GroupRoster.transferPayload(new.groupId, own.epoch, own.from, own.to, own.toFingerprint), own.signature, own.pqcSignature, issuerKeys)) {
                return Verdict.Reject("handover signature")
            }
        }
        val issuerKeys = keys(new.issuer) ?: return Verdict.NeedKeys(new.issuer, vouched[new.issuer])
        if (!signatures.verify(new.canonical(), new.signature, new.pqcSignature, issuerKeys)) {
            return Verdict.Reject("roster signature")
        }
        return Verdict.Accept
    }

    /** [roster] signed by the local account (its issuer). */
    fun sign(roster: GroupRoster, signatures: GroupSignatures): GroupRoster {
        val (sig, pq) = signatures.sign(roster.unsigned().canonical())
        return roster.copy(signature = sig, pqcSignature = pq)
    }

    /** The handover at [epoch] from [from] (the local account) to [to], whose keys are [toFingerprint], signed. */
    fun transfer(groupId: String, epoch: Long, from: String, to: String, toFingerprint: String, signatures: GroupSignatures): AdminTransfer {
        val (sig, pq) = signatures.sign(GroupRoster.transferPayload(groupId, epoch, from, to, toFingerprint))
        return AdminTransfer(epoch, from, to, toFingerprint, sig, pq)
    }

    /** Member names: no control or text-direction characters, at most [GroupRoster.MAX_NAME]. */
    fun cleanName(name: String): String =
        name.filterNot { it.isISOControl() || it in '‎'..'‏' || it in '‪'..'‮' || it in '⁦'..'⁩' }
            .trim().take(GroupRoster.MAX_NAME)

    fun cleanTitle(title: String): String =
        title.filterNot { it.isISOControl() || it in '‎'..'‏' || it in '‪'..'‮' || it in '⁦'..'⁩' }
            .trim().take(GroupRoster.MAX_TITLE)

    internal fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
