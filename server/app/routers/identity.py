"""Identity endpoints: bootstrap, key bundles, share code resolution.

The server stores public keys and a random user ID. It never sees
private keys or any personally identifiable information.
"""

import base64
import hashlib
import logging
import secrets

from fastapi import APIRouter, Depends, HTTPException, Path
from sqlalchemy import delete, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app import config
from app.auth import key_lookup_auth, verify_auth_token
from app.database import get_db
from app.models.db import AttachmentRecipient, DeliveryReceipt, Identity, Invite, PendingMessage, RateLimit, ReadReceipt, RetiredShareCode
from app.services import invites
from app.services.call_rate_limiter import SlidingWindowLimiter
from app.services.ip_rate_limiter import check_ip_rate_limit
from app.services.websocket_manager import ws_manager
from app.models.schemas import (
    BootstrapRequest,
    BootstrapResponse,
    KeyBundleResponse,
    KeyRotationRequest,
    ResolveShareCodeResponse,
)

router = APIRouter(prefix="/v1/identity", tags=["identity"])

# Key rotations per account. Each one retires a share code for good (a row
# that is never purged) and checks up to four signatures, and the app rotates
# rarely, so a handful an hour is plenty.
rotation_limiter = SlidingWindowLimiter(limit=5, window=3600.0)


@router.post("/bootstrap", response_model=BootstrapResponse, dependencies=[Depends(check_ip_rate_limit)])
async def bootstrap_identity(
    request: BootstrapRequest,
    db: AsyncSession = Depends(get_db),
) -> BootstrapResponse:
    """Register a new guest identity.

    Generates a random user_id and a share code derived from the fingerprint
    of ALL public keys (see ``compute_fingerprint``). Clients recompute the
    fingerprint when resolving a share code, so the server cannot substitute keys.

    On an invite-only relay (``INVITE_ONLY``) every account after its first one
    needs a member's one-time invite (``invite_code``), used up in this same
    transaction. Signing up again with the very same keys returns that account,
    so a lost reply can't use up an invite.
    """
    # Validate that submitted keys are well-formed Base64 before storing.
    # This prevents garbage data from causing decode errors in other clients.
    for field_name, value in [
        ("public_signing_key", request.public_signing_key),
        ("public_identity_key", request.public_identity_key),
    ]:
        if not value:
            raise HTTPException(status_code=400, detail=f"{field_name} is required")
        try:
            base64.b64decode(value, validate=True)
        except Exception:
            raise HTTPException(status_code=400, detail=f"Invalid base64 in {field_name}")
    for field_name, value in [
        ("pqc_encapsulation_key", request.pqc_encapsulation_key),
        ("pqc_signing_key", request.pqc_signing_key),
    ]:
        if value:
            try:
                base64.b64decode(value, validate=True)
            except Exception:
                raise HTTPException(status_code=400, detail=f"Invalid base64 in {field_name}")

    user_id = secrets.token_hex(8)  # 16 chars
    share_code = _derive_share_code(
        request.public_identity_key,
        request.public_signing_key,
        request.pqc_encapsulation_key,
        request.pqc_signing_key,
    )

    # A retired code belongs to keys that were rotated away or deleted: someone is
    # re-registering an old (public) key bundle to take over that code.
    if await _is_retired(db, share_code):
        raise HTTPException(status_code=409, detail="These keys were retired; generate new keys")

    # The share code is a fingerprint of all four keys: the same code is either the
    # same keys again (an earlier sign-up whose reply got lost: the same account) or
    # an astronomically unlikely collision.
    existing = (await db.execute(
        select(Identity).where(Identity.share_code == share_code)
    )).scalar_one_or_none()
    if existing is not None:
        if _same_keys(existing, request):
            return BootstrapResponse(user_id=existing.user_id, share_code=existing.share_code)
        raise HTTPException(status_code=409, detail="Identity collision — retry")

    # Every account marks the relay founded. After its first account, an
    # invite-only relay wants an invite, spent in this transaction.
    founding = await invites.claim_founding(db)
    if config.INVITE_ONLY and not founding:
        await invites.redeem(db, request.invite_code, user_id)

    identity = Identity(
        user_id=user_id,
        public_signing_key=request.public_signing_key,
        public_identity_key=request.public_identity_key,
        pqc_encapsulation_key=request.pqc_encapsulation_key,
        pqc_signing_key=request.pqc_signing_key,
        share_code=share_code,
        fcm_token=request.fcm_token,
    )
    try:
        db.add(identity)
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(status_code=409, detail="Identity collision — retry")

    return BootstrapResponse(user_id=user_id, share_code=share_code)


@router.get("/{user_id}/keys", response_model=KeyBundleResponse, dependencies=[Depends(check_ip_rate_limit), Depends(key_lookup_auth)])
async def fetch_key_bundle(
    user_id: str = Path(..., pattern=r"^[a-f0-9]{16}$"),
    db: AsyncSession = Depends(get_db),
) -> KeyBundleResponse:
    """Fetch a user's public key bundle for session establishment."""
    result = await db.execute(
        select(Identity).where(Identity.user_id == user_id)
    )
    identity = result.scalar_one_or_none()
    if identity is None:
        raise HTTPException(status_code=404, detail="User not found")

    return KeyBundleResponse(
        user_id=identity.user_id,
        public_signing_key=identity.public_signing_key,
        public_identity_key=identity.public_identity_key,
        pqc_encapsulation_key=identity.pqc_encapsulation_key,
        pqc_signing_key=identity.pqc_signing_key,
        rotation_prev_fingerprint=identity.rotation_prev_fingerprint or "",
        rotation_signature=identity.rotation_signature or "",
        rotation_pqc_signature=identity.rotation_pqc_signature or "",
    )


@router.get("/resolve/{share_code}", response_model=ResolveShareCodeResponse, dependencies=[Depends(check_ip_rate_limit), Depends(key_lookup_auth)])
async def resolve_share_code(
    share_code: str = Path(..., pattern=r"^[A-Za-z0-9_-]{16}$"),
    db: AsyncSession = Depends(get_db),
) -> ResolveShareCodeResponse:
    """Resolve a share code to a user's public key bundle.

    This is how contact discovery works: User A gives their share code
    to User B (via QR code, text, etc.), and User B resolves it here.
    """
    result = await db.execute(
        select(Identity).where(Identity.share_code == share_code)
    )
    identity = result.scalar_one_or_none()
    if identity is None:
        raise HTTPException(status_code=404, detail="Share code not found")

    return ResolveShareCodeResponse(
        user_id=identity.user_id,
        public_signing_key=identity.public_signing_key,
        public_identity_key=identity.public_identity_key,
        pqc_encapsulation_key=identity.pqc_encapsulation_key,
        pqc_signing_key=identity.pqc_signing_key,
    )


@router.delete("/me")
async def delete_identity(
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> dict:
    """Delete the authenticated user's identity and all associated data.

    Removes the identity, all pending messages addressed to it, and
    rate-limit records. This is irreversible.

    Messages the user already sent stay queued for their recipients: the app
    sends every contact an ``account_deleted`` notice right before calling
    this, and deleting it here would leave offline contacts writing to a
    deleted account. They expire like any other pending message.
    """
    # Identity first: a concurrent send to this user holds the identity row
    # lock (FOR UPDATE), so this waits for it to commit, and the delete below
    # then also removes the message it inserted.
    old_code = (await db.execute(
        select(Identity.share_code).where(Identity.user_id == x_user_id)
    )).scalar_one_or_none()
    result = await db.execute(delete(Identity).where(Identity.user_id == x_user_id))
    if result.rowcount == 0:
        raise HTTPException(status_code=404, detail="User not found")
    await _retire(db, old_code)
    await db.execute(delete(PendingMessage).where(PendingMessage.recipient_id == x_user_id))
    await db.execute(delete(ReadReceipt).where(
        (ReadReceipt.recipient_id == x_user_id) | (ReadReceipt.sender_id == x_user_id)
    ))
    await db.execute(delete(DeliveryReceipt).where(
        (DeliveryReceipt.recipient_id == x_user_id) | (DeliveryReceipt.sender_id == x_user_id)
    ))
    await db.execute(delete(RateLimit).where(RateLimit.user_id == x_user_id))
    # Files waiting for this account can't be fetched by it any more (the purge removes a file
    # once nobody is left to fetch it).
    await db.execute(delete(AttachmentRecipient).where(AttachmentRecipient.recipient_id == x_user_id))
    # Invites nobody used yet die with their member.
    await db.execute(delete(Invite).where(Invite.created_by == x_user_id, Invite.used_at.is_(None)))
    await db.commit()
    # Live sockets of a deleted identity must not keep receiving signals or
    # relaying call signals under its user id.
    await ws_manager.disconnect_user(x_user_id, "Identity deleted")
    return {"status": "deleted"}


@router.put("/me/keys")
async def rotate_keys(
    request: KeyRotationRequest,
    x_user_id: str = Depends(verify_auth_token),
    db: AsyncSession = Depends(get_db),
) -> dict:
    """Rotate the authenticated user's public keys.

    The request is authenticated with the *current* signing key.
    The caller must also prove possession of the *new* signing key by
    signing the new public_signing_key with the corresponding new private key.
    After this call, subsequent auth tokens must be signed with the new key.

    The caller must also endorse the new key set with the *current* keys
    (``rotation_signature`` / ``rotation_pqc_signature`` over
    ``rotation_payload(user_id, old_fingerprint, new_fingerprint)``). The
    endorsement is published with the key bundle; contacts accept the new
    keys only if it verifies against the keys they already trust.
    """
    # Before any signature check: failed attempts count too.
    if not await rotation_limiter.allow(x_user_id):
        raise HTTPException(status_code=429, detail="key_rotation_rate_limited")
    # Verify proof-of-possession for the new signing key
    try:
        new_pub_bytes = base64.b64decode(request.public_signing_key, validate=True)
        proof_sig = base64.b64decode(request.new_key_proof, validate=True)
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid base64 in key rotation request")

    from cryptography.exceptions import InvalidSignature
    try:
        new_pub_key = _load_ed25519_public_key(new_pub_bytes)
        new_pub_key.verify(proof_sig, request.public_signing_key.encode("utf-8"))
    except InvalidSignature:
        raise HTTPException(status_code=400, detail="New key proof-of-possession failed")
    except (ValueError, TypeError) as e:
        logging.getLogger(__name__).warning("Key proof validation error: %s", e)
        raise HTTPException(status_code=400, detail="New key proof-of-possession failed")

    # Verify ML-DSA-65 proof-of-possession for the new PQC signing key.
    # If a PQC key is provided, proof is REQUIRED — otherwise an attacker
    # could inject an arbitrary key without proving possession.
    if request.pqc_signing_key:
        if not request.pqc_key_proof:
            raise HTTPException(status_code=400, detail="pqc_key_proof required when rotating PQC signing key")
        from app.auth import _extract_mldsa_raw_pk, _verify_mldsa_signature
        try:
            pqc_pub_bytes = base64.b64decode(request.pqc_signing_key, validate=True)
            pqc_proof_sig = base64.b64decode(request.pqc_key_proof, validate=True)
        except Exception:
            raise HTTPException(status_code=400, detail="Invalid base64 in PQC key rotation")
        raw_pk = _extract_mldsa_raw_pk(pqc_pub_bytes)
        if raw_pk is None:
            raise HTTPException(status_code=400, detail="Invalid ML-DSA-65 public key format")
        if not _verify_mldsa_signature(raw_pk, request.pqc_signing_key.encode("utf-8"), pqc_proof_sig):
            raise HTTPException(status_code=400, detail="PQC key proof-of-possession failed")

    result = await db.execute(
        select(Identity).where(Identity.user_id == x_user_id)
    )
    identity = result.scalar_one_or_none()
    if identity is None:
        raise HTTPException(status_code=404, detail="User not found")

    # Prevent clearing an existing PQC signing key without a valid new one.
    # An attacker who compromises Ed25519 could otherwise downgrade the user
    # from hybrid PQC+Ed25519 auth back to Ed25519-only by sending an empty
    # pqc_signing_key — exactly the scenario hybrid PQC auth is designed to prevent.
    if identity.pqc_signing_key and not request.pqc_signing_key:
        raise HTTPException(status_code=400, detail="Cannot clear PQC signing key once set")

    # Contacts can't set up sessions without an identity key (bootstrap requires one too).
    if not request.public_identity_key:
        raise HTTPException(status_code=400, detail="public_identity_key is required")

    # Validate key fields as well-formed Base64 (matching bootstrap validation)
    for field_name, value in [
        ("public_identity_key", request.public_identity_key),
        ("pqc_encapsulation_key", request.pqc_encapsulation_key),
    ]:
        if not value:
            continue
        try:
            base64.b64decode(value, validate=True)
        except Exception:
            raise HTTPException(status_code=400, detail=f"Invalid base64 in {field_name}")

    new_pqc_encapsulation_key = (
        request.pqc_encapsulation_key
        if request.pqc_encapsulation_key is not None
        else identity.pqc_encapsulation_key
    )
    new_pqc_signing_key = request.pqc_signing_key or identity.pqc_signing_key

    # Verify the endorsement of the new key set by the CURRENT keys. Clients
    # verify this themselves; checking it here makes a broken rotation fail
    # loudly instead of silently cutting the user off from their contacts.
    old_fp = _fingerprint_or_400(
        identity.public_identity_key, identity.public_signing_key,
        identity.pqc_encapsulation_key, identity.pqc_signing_key,
    )
    new_fp = _fingerprint_or_400(
        request.public_identity_key, request.public_signing_key,
        new_pqc_encapsulation_key, new_pqc_signing_key,
    )
    if request.rotation_prev_fingerprint != old_fp.hex():
        raise HTTPException(status_code=400, detail="Rotation endorsement must reference the current key set")
    payload = rotation_payload(x_user_id, old_fp, new_fp)
    try:
        endorsement_sig = base64.b64decode(request.rotation_signature, validate=True)
        old_ed_key = _load_ed25519_public_key(base64.b64decode(identity.public_signing_key))
        old_ed_key.verify(endorsement_sig, payload)
    except Exception:
        raise HTTPException(status_code=400, detail="Rotation endorsement signature invalid")
    if identity.pqc_signing_key:
        from app.auth import _extract_mldsa_raw_pk, _verify_mldsa_signature
        try:
            endorsement_pqc_sig = base64.b64decode(request.rotation_pqc_signature, validate=True)
        except Exception:
            raise HTTPException(status_code=400, detail="Invalid base64 in rotation_pqc_signature")
        old_raw_pk = _extract_mldsa_raw_pk(base64.b64decode(identity.pqc_signing_key))
        if (
            not endorsement_pqc_sig
            or old_raw_pk is None
            or not _verify_mldsa_signature(old_raw_pk, payload, endorsement_pqc_sig)
        ):
            raise HTTPException(status_code=400, detail="Rotation ML-DSA endorsement invalid")

    identity.public_signing_key = request.public_signing_key
    identity.public_identity_key = request.public_identity_key
    identity.pqc_encapsulation_key = new_pqc_encapsulation_key
    identity.pqc_signing_key = new_pqc_signing_key
    identity.rotation_prev_fingerprint = old_fp.hex()
    identity.rotation_signature = request.rotation_signature
    identity.rotation_pqc_signature = request.rotation_pqc_signature

    # Recompute share code from the new key set.
    # Collision is caught by IntegrityError on commit (if a unique constraint
    # exists) — no pre-check needed since 96-bit collisions are astronomically
    # rare and the pre-check itself has a TOCTOU window.
    new_code = share_code_from_fingerprint(new_fp)
    if await _is_retired(db, new_code):
        raise HTTPException(status_code=409, detail="These keys were retired; generate new keys")
    await _retire(db, identity.share_code)
    identity.share_code = new_code

    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(status_code=409, detail="Share code collision — retry with different keys")
    # Sockets authenticated with the old keys (possibly by whoever leaked them —
    # rotation is the recovery path) must not survive the rotation.
    await ws_manager.disconnect_user(x_user_id, "Keys rotated")
    return {"status": "ok", "share_code": identity.share_code}


async def _is_retired(db: AsyncSession, share_code: str) -> bool:
    return (await db.get(RetiredShareCode, share_code)) is not None


async def _retire(db: AsyncSession, share_code: str | None) -> None:
    """Mark a share code as never to be registered again (same transaction)."""
    if share_code and not await _is_retired(db, share_code):
        db.add(RetiredShareCode(share_code=share_code))


FINGERPRINT_DOMAIN = b"ChatControll-fingerprint-v1"
ROTATION_DOMAIN = b"ChatControll-key-rotation-v1"
SHARE_CODE_BYTES = 12


def _lp(data: bytes) -> bytes:
    return len(data).to_bytes(4, "big") + data


def _same_keys(identity: Identity, request: BootstrapRequest) -> bool:
    return (
        identity.public_signing_key == request.public_signing_key
        and identity.public_identity_key == request.public_identity_key
        and identity.pqc_encapsulation_key == request.pqc_encapsulation_key
        and identity.pqc_signing_key == request.pqc_signing_key
    )


def compute_fingerprint(
    public_identity_key_b64: str,
    public_signing_key_b64: str,
    pqc_encapsulation_key_b64: str,
    pqc_signing_key_b64: str,
) -> bytes:
    """SHA-256 fingerprint over all public keys.

    Must match the client's ``KeyFingerprint.compute``:
    ``SHA-256(domain || LP(identity) || LP(signing) || LP(pqc_enc) || LP(pqc_sign))``
    where LP is a 4-byte big-endian length prefix and empty keys are zero-length.
    Raises ``ValueError`` on malformed base64.
    """
    h = hashlib.sha256(FINGERPRINT_DOMAIN)
    for value in (
        public_identity_key_b64,
        public_signing_key_b64,
        pqc_encapsulation_key_b64,
        pqc_signing_key_b64,
    ):
        raw = base64.b64decode(value, validate=True) if value else b""
        h.update(_lp(raw))
    return h.digest()


def share_code_from_fingerprint(fingerprint: bytes) -> str:
    """Share code = first 96 bits of the fingerprint, base64url without padding.

    Share codes are public lookup handles (displayed in UI, shared via QR) and
    commitments to the key bundle: a client that resolves a share code checks
    that the returned keys hash to it.
    """
    return base64.urlsafe_b64encode(fingerprint[:SHARE_CODE_BYTES]).decode().rstrip("=")


def rotation_payload(user_id: str, previous_fingerprint: bytes, new_fingerprint: bytes) -> bytes:
    """Payload the OLD keys sign to endorse a new key set (matches the client)."""
    return (
        ROTATION_DOMAIN
        + _lp(user_id.encode("utf-8"))
        + _lp(previous_fingerprint)
        + _lp(new_fingerprint)
    )


def _fingerprint_or_400(*keys_b64: str) -> bytes:
    try:
        return compute_fingerprint(*keys_b64)
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid base64 in key material")


def _derive_share_code(
    public_identity_key_b64: str,
    public_signing_key_b64: str,
    pqc_encapsulation_key_b64: str,
    pqc_signing_key_b64: str,
) -> str:
    return share_code_from_fingerprint(
        _fingerprint_or_400(
            public_identity_key_b64,
            public_signing_key_b64,
            pqc_encapsulation_key_b64,
            pqc_signing_key_b64,
        )
    )


def _load_ed25519_public_key(key_bytes: bytes):
    """Load an Ed25519 public key from raw (32 bytes) or SPKI/DER encoding."""
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    from cryptography.hazmat.primitives.serialization import load_der_public_key

    if len(key_bytes) == 32:
        return Ed25519PublicKey.from_public_bytes(key_bytes)
    key = load_der_public_key(key_bytes)
    if not isinstance(key, Ed25519PublicKey):
        raise ValueError("Not an Ed25519 key")
    return key
