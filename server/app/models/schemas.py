"""Pydantic request/response schemas matching the Android client DTOs."""

from typing import Annotated, List, Optional

from pydantic import BaseModel, Field, model_validator


class BootstrapRequest(BaseModel):
    public_signing_key: str = Field(..., max_length=4096)
    public_identity_key: str = Field(..., max_length=4096)
    pqc_encapsulation_key: str = Field("", max_length=8192)
    pqc_signing_key: str = Field("", max_length=8192)
    fcm_token: Optional[str] = Field(None, max_length=4096)
    # Needed on an invite-only relay once its first account exists.
    invite_code: str = Field("", max_length=64)


class BootstrapResponse(BaseModel):
    user_id: str
    share_code: str


class KeyBundleResponse(BaseModel):
    user_id: str
    public_signing_key: str
    public_identity_key: str
    pqc_encapsulation_key: str
    pqc_signing_key: str
    # Endorsement of these keys by the previous key set (empty if never rotated)
    rotation_prev_fingerprint: str = ""
    rotation_signature: str = ""
    rotation_pqc_signature: str = ""


class ResolveShareCodeResponse(BaseModel):
    user_id: str
    public_signing_key: str
    public_identity_key: str
    pqc_encapsulation_key: str
    pqc_signing_key: str


class KeyRotationRequest(BaseModel):
    public_signing_key: str = Field(..., max_length=4096)
    public_identity_key: str = Field(..., max_length=4096)
    pqc_encapsulation_key: Optional[str] = Field(None, max_length=8192)
    pqc_signing_key: Optional[str] = Field(None, max_length=8192)
    new_key_proof: str = Field(..., max_length=512, description="Signature of the new public_signing_key by the new private key (proof of possession)")
    pqc_key_proof: str = Field("", max_length=4608, description="ML-DSA-65 signature of the new pqc_signing_key by the new ML-DSA private key (proof of possession)")
    # Required (validated in the handler so ordering of error responses is stable)
    rotation_prev_fingerprint: str = Field("", max_length=64, description="Hex fingerprint of the current (old) key set")
    rotation_signature: str = Field("", max_length=512, description="Ed25519 signature by the OLD signing key over the rotation payload")
    rotation_pqc_signature: str = Field("", max_length=4608, description="ML-DSA-65 signature by the OLD PQC signing key over the rotation payload (required if one is set)")


class SendMessageRequest(BaseModel):
    recipient_id: str = Field(..., max_length=64)
    encrypted_body: str = Field(..., max_length=262_144)  # 256 KB — sufficient for chat messages
    nonce: str = Field(..., max_length=4096)  # Carries ratchet header JSON; first PQC msg includes ML-KEM ciphertext (~1.6 KB)
    ephemeral_public_key: str = Field("", max_length=4096)
    signature: str = Field("", max_length=512)
    pqc_signature: str = Field("", max_length=4608)  # ML-DSA-65 signature
    # Sender's local id for this message. A resend after a lost response reuses
    # it, so the relay can recognise the message instead of storing it twice.
    client_message_id: str = Field("", max_length=64)


class SendMessageResponse(BaseModel):
    message_id: str
    timestamp: int


class PendingMessageResponse(BaseModel):
    message_id: str
    sender_id: str
    encrypted_body: str
    nonce: str
    ephemeral_public_key: str = ""
    signature: str = ""
    pqc_signature: str = ""
    timestamp: int


class AckRequest(BaseModel):
    message_ids: List[Annotated[str, Field(max_length=64)]] = Field(..., max_length=1000)


class ReceiptRequest(BaseModel):
    recipient_id: str = Field(..., max_length=64)
    message_ids: List[Annotated[str, Field(min_length=1, max_length=64)]] = Field(..., min_length=1, max_length=100)
    signature: str = Field(..., min_length=1, max_length=512)


class ReceiptResponse(BaseModel):
    id: int
    sender_id: str
    message_ids: List[str]
    signature: str
    timestamp: int


class DeliveryReceiptResponse(BaseModel):
    """Messages of ours that reached ``sender_id``'s phone (the relay's word, unsigned)."""

    id: int
    sender_id: str
    message_ids: List[str]
    timestamp: int


class ReceiptAckRequest(BaseModel):
    ids: List[int] = Field(..., max_length=1000)


class PushTokenRequest(BaseModel):
    """A device's push target: its Firebase installation ID (apps 0.5.5+) or,
    from older apps, its FCM registration token. Exactly one of the two."""

    token: Optional[str] = Field(None, min_length=1, max_length=4096)
    fid: Optional[str] = Field(None, min_length=1, max_length=256, pattern=r"^[A-Za-z0-9_-]+$")
    platform: str = "android"

    @model_validator(mode="after")
    def _exactly_one_target(self) -> "PushTokenRequest":
        if (self.token is None) == (self.fid is None):
            raise ValueError("send exactly one of token or fid")
        return self


class ErrorResponse(BaseModel):
    code: str
    message: str


class HealthResponse(BaseModel):
    status: str = "ok"
    version: str = "0.6.0"
    # Lets an app tell a Sotto relay from any other server...
    service: str = "sotto-relay"
    # ...and whether a new account here needs an invite.
    invite_required: bool = False


class InviteResponse(BaseModel):
    code: str
    expires_at: str


class CallSignalRequest(BaseModel):
    recipient_id: str = Field(..., max_length=64)
    signal_type: str = Field(..., max_length=64)
    call_id: str = Field(..., max_length=128)
    encrypted_payload: str = Field(..., max_length=65536)
    signature: str = Field("", max_length=512)
    pqc_signature: str = Field("", max_length=4608)  # ML-DSA-65 signature (~4412 B base64)
    kem_ciphertext: str = Field("", max_length=2048)  # ML-KEM-768 ciphertext (~1452 B base64); only on call_offer


class CallSignalResponse(BaseModel):
    delivered: bool


class IceServer(BaseModel):
    urls: str
    username: Optional[str] = None
    credential: Optional[str] = None


class IceServersResponse(BaseModel):
    ice_servers: List[IceServer]
