"""Helpers for building key-rotation requests with a valid endorsement."""

import base64

from app.routers.identity import compute_fingerprint, rotation_payload


def endorse(
    body: dict,
    *,
    user_id: str,
    old_identity_b64: str,
    old_signing_b64: str,
    ed_private,
    old_pqc_enc_b64: str = "",
    old_pqc_sign_b64: str = "",
    mldsa_private: bytes = b"",
) -> dict:
    """Return ``body`` plus the rotation endorsement signed by the OLD keys.

    New PQC keys fall back to the old ones when absent, mirroring the server.
    """
    new_pqc_enc = body.get("pqc_encapsulation_key")
    if new_pqc_enc is None:
        new_pqc_enc = old_pqc_enc_b64
    new_pqc_sign = body.get("pqc_signing_key") or old_pqc_sign_b64

    old_fp = compute_fingerprint(old_identity_b64, old_signing_b64, old_pqc_enc_b64, old_pqc_sign_b64)
    new_fp = compute_fingerprint(
        body["public_identity_key"], body["public_signing_key"], new_pqc_enc, new_pqc_sign,
    )
    payload = rotation_payload(user_id, old_fp, new_fp)

    out = dict(body)
    out["rotation_prev_fingerprint"] = old_fp.hex()
    out["rotation_signature"] = base64.b64encode(ed_private.sign(payload)).decode()
    if mldsa_private:
        from pqcrypto.sign.ml_dsa_65 import sign
        out["rotation_pqc_signature"] = base64.b64encode(sign(mldsa_private, payload)).decode()
    return out
