"""Share codes over the full key fingerprint + key-rotation endorsements.

- Adds rotation_prev_fingerprint / rotation_signature / rotation_pqc_signature
  to identities (the latest endorsement of the current keys by the previous ones).
- Recomputes every share code as a commitment to ALL public keys
  (identity, signing, ML-KEM, ML-DSA) instead of the identity key only.
  Clients verify resolved bundles against the share code, so existing codes
  must be recomputed; clients derive their own code locally and pick up the
  new value automatically.

Revision ID: 005
Revises: 004
Create Date: 2026-11-18
"""
import base64
import hashlib
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "005"
down_revision: Union[str, None] = "004"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _share_code(identity_b64: str, signing_b64: str, pqc_enc_b64: str, pqc_sign_b64: str) -> str:
    # Frozen copy of app.routers.identity.compute_fingerprint (migrations must
    # not import application code that may change later).
    h = hashlib.sha256(b"ChatControll-fingerprint-v1")
    for value in (identity_b64, signing_b64, pqc_enc_b64, pqc_sign_b64):
        raw = base64.b64decode(value) if value else b""
        h.update(len(raw).to_bytes(4, "big"))
        h.update(raw)
    return base64.urlsafe_b64encode(h.digest()[:12]).decode().rstrip("=")


def upgrade() -> None:
    for column in ("rotation_prev_fingerprint", "rotation_signature", "rotation_pqc_signature"):
        op.add_column(
            "identities",
            sa.Column(column, sa.Text, nullable=False, server_default=""),
        )

    conn = op.get_bind()
    rows = conn.execute(sa.text(
        "SELECT user_id, public_identity_key, public_signing_key, "
        "pqc_encapsulation_key, pqc_signing_key FROM identities"
    )).fetchall()
    for user_id, identity, signing, pqc_enc, pqc_sign in rows:
        conn.execute(
            sa.text("UPDATE identities SET share_code = :code WHERE user_id = :uid"),
            {"code": _share_code(identity, signing, pqc_enc or "", pqc_sign or ""), "uid": user_id},
        )


def downgrade() -> None:
    # Share codes are not restored to the identity-key-only derivation.
    for column in ("rotation_pqc_signature", "rotation_signature", "rotation_prev_fingerprint"):
        op.drop_column("identities", column)
