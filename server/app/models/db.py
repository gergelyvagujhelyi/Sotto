"""SQLAlchemy ORM models for the relay server.

The server stores only what it must to route encrypted envelopes:
- Identity: userId, public keys, share code, push token
- PendingMessage: encrypted envelopes awaiting delivery
"""

from datetime import datetime

from sqlalchemy import (
    BigInteger,
    Boolean,
    Column,
    DateTime,
    Float,
    Index,
    Integer,
    String,
    Text,
    func,
)
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass


class Identity(Base):
    """A registered guest identity. No PII — only cryptographic material."""

    __tablename__ = "identities"

    user_id = Column(String(32), primary_key=True)
    public_signing_key = Column(Text, nullable=False)
    public_identity_key = Column(Text, nullable=False)
    pqc_encapsulation_key = Column(Text, nullable=False, default="")
    pqc_signing_key = Column(Text, nullable=False, default="")
    share_code = Column(String(24), unique=True, nullable=False, index=True)
    # Latest key-rotation endorsement (signatures by the previous key set)
    rotation_prev_fingerprint = Column(Text, nullable=False, default="")
    rotation_signature = Column(Text, nullable=False, default="")
    rotation_pqc_signature = Column(Text, nullable=False, default="")
    # Push target: the Firebase installation ID (apps 0.5.5+) or, from older apps,
    # the FCM registration token. At most one of the two is set.
    fcm_token = Column(Text, nullable=True)
    fcm_fid = Column(Text, nullable=True)
    created_at = Column(DateTime, server_default=func.now())
    last_seen_at = Column(DateTime, server_default=func.now())
    # The app version this account last used, and since when (apps 0.7.24+ say it).
    app_version = Column(String(32), nullable=True)
    app_version_at = Column(DateTime, nullable=True)



class PendingMessage(Base):
    """An encrypted envelope waiting for the recipient to fetch it."""

    __tablename__ = "pending_messages"
    __table_args__ = (Index("ix_pending_recipient", "recipient_id", "created_at"),)

    id = Column(Integer, primary_key=True, autoincrement=True)
    message_id = Column(String(64), unique=True, nullable=False, index=True)
    sender_id = Column(String(32), nullable=False)
    recipient_id = Column(String(32), nullable=False)
    encrypted_body = Column(Text, nullable=False)
    nonce = Column(Text, nullable=False)
    ephemeral_public_key = Column(Text, nullable=False, default="")
    signature = Column(Text, nullable=False, default="")
    pqc_signature = Column(Text, nullable=False, default="")
    timestamp_ms = Column(BigInteger, nullable=True)
    created_at = Column(DateTime, server_default=func.now())


class RateLimit(Base):
    """Each account's message-send token bucket (`services/rate_limiter.py`).

    `message_count` and `window_start` belonged to the fixed window used until the bucket
    replaced it (migration 013); they are no longer read.
    """

    __tablename__ = "rate_limits"

    user_id = Column(String(32), primary_key=True)
    message_count = Column(Integer, default=0)
    window_start = Column(DateTime, server_default=func.now())
    tokens = Column(Float, nullable=True)
    refilled_at = Column(DateTime, nullable=True)


class Invite(Base):
    """A one-time invitation a member created, for someone to join this relay.

    Only the SHA-256 of the code is kept, so this table can't be used to join.
    """

    __tablename__ = "invites"

    code_hash = Column(String(64), primary_key=True)
    created_by = Column(String(32), nullable=False, index=True)
    created_at = Column(DateTime, nullable=False)
    expires_at = Column(DateTime, nullable=False)
    used_by = Column(String(32), nullable=True)
    used_at = Column(DateTime, nullable=True)


class RelayState(Base):
    """One row about the relay itself.

    ``founded``: its first account exists, so with invite-only sign-up every
    later account needs an invite. Never reset, so a relay whose members all
    left doesn't open up again.
    """

    __tablename__ = "relay_state"

    id = Column(Integer, primary_key=True)
    founded = Column(Boolean, nullable=False, default=False)


class RetiredShareCode(Base):
    """A share code whose key set was rotated away or deleted.

    Share codes are fingerprints of public keys, so anyone holding an old,
    public key bundle could otherwise register it again and take over the old
    code (and with leaked private keys, impersonate its owner to whoever still
    uses the old code). Retired codes can never be registered again.
    """

    __tablename__ = "retired_share_codes"

    share_code = Column(String(24), primary_key=True)
    retired_at = Column(DateTime, server_default=func.now())


class ReadReceipt(Base):
    """A read receipt waiting for the original sender to fetch it.

    Kept apart from pending messages so clients that don't know receipts never
    see them. ``message_ids`` is a JSON list of relay message ids; the reader's
    signature over them is verified by the recipient, not the relay.
    """

    __tablename__ = "read_receipts"
    __table_args__ = (Index("ix_receipt_recipient", "recipient_id", "id"),)

    id = Column(Integer, primary_key=True, autoincrement=True)
    sender_id = Column(String(32), nullable=False)
    recipient_id = Column(String(32), nullable=False)
    message_ids = Column(Text, nullable=False)
    signature = Column(Text, nullable=False)
    created_at = Column(DateTime, server_default=func.now())


class DeliveryReceipt(Base):
    """A delivery receipt waiting for the original sender to fetch it.

    The relay writes it itself when the recipient acks (has fetched) messages, so
    there is no signature: the relay is the one that knows. As for read receipts,
    ``sender_id`` is the phone that got the messages and ``recipient_id`` their
    author; ``message_ids`` is a JSON list of relay message ids.
    """

    __tablename__ = "delivery_receipts"
    __table_args__ = (Index("ix_delivery_receipt_recipient", "recipient_id", "id"),)

    id = Column(Integer, primary_key=True, autoincrement=True)
    sender_id = Column(String(32), nullable=False)
    recipient_id = Column(String(32), nullable=False)
    message_ids = Column(Text, nullable=False)
    created_at = Column(DateTime, server_default=func.now())


class Attachment(Base):
    """An encrypted file in the object store, on its way from sender to recipient (ADR-29).

    The relay only knows who sends to whom and how big the ciphertext is; the key
    travels inside the end-to-end encrypted message. The row (and the object) go
    when every recipient (`AttachmentRecipient`) has it, or after ATTACHMENT_TTL_DAYS.
    """

    __tablename__ = "attachments"

    id = Column(String(32), primary_key=True)
    sender_id = Column(String(32), nullable=False, index=True)
    recipient_id = Column(String(32), nullable=False, index=True)
    size = Column(BigInteger, nullable=False)
    created_at = Column(DateTime, nullable=False, index=True)
    uploaded_at = Column(DateTime, nullable=True)
    expires_at = Column(DateTime, nullable=False, index=True)
    # A large file goes up in parts (resumable): the store's id for that multipart upload.
    upload_id = Column(String(512), nullable=True)


class AttachmentRecipient(Base):
    """Who may download an attachment: one row per recipient, removed when that recipient
    has the file. A file for a group goes up once and names every member who gets it; the
    object goes when the last of them has it (or the sender gives up, or it expires).
    `Attachment.recipient_id` keeps the first recipient, as before.
    """

    __tablename__ = "attachment_recipients"

    attachment_id = Column(String(32), primary_key=True)
    recipient_id = Column(String(32), primary_key=True, index=True)


class AttachmentUsage(Base):
    """Bytes an account reserved for upload, kept after the file is gone, for its monthly allowance."""

    __tablename__ = "attachment_usage"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(String(32), nullable=False, index=True)
    size = Column(BigInteger, nullable=False)
    created_at = Column(DateTime, nullable=False, index=True)
