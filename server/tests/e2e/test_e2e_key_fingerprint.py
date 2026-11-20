"""Share codes as key fingerprints, rotation endorsements, ML-DSA fail-closed."""

import base64
import time

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from httpx import AsyncClient

from app.routers.identity import compute_fingerprint, rotation_payload, share_code_from_fingerprint
from tests.e2e.conftest import User, bootstrap_user


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode()


def _new_ed25519():
    pk = Ed25519PrivateKey.generate()
    pub = pk.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return pk, _b64(pub)


def _rotation_body(new_pk, new_pub_b64: str, new_id_b64: str) -> dict:
    return {
        "public_signing_key": new_pub_b64,
        "public_identity_key": new_id_b64,
        "new_key_proof": _b64(new_pk.sign(new_pub_b64.encode("utf-8"))),
    }


def test_share_code_matches_client_fingerprint_vector():
    """Same vector as the Android KeyFingerprintTest — client and server must agree."""
    identity = bytes(i & 0xFF for i in range(44))
    signing = bytes((i + 100) & 0xFF for i in range(44))
    pqc_enc = bytes((i * 7) & 0xFF for i in range(1184))
    pqc_sign = bytes((i * 13) & 0xFF for i in range(1952))
    fp = compute_fingerprint(_b64(identity), _b64(signing), _b64(pqc_enc), _b64(pqc_sign))
    assert fp.hex() == "d6eb09fc8af328b0dbd7946a1439837861930c9351c29797f6c04e6fb600090e"
    assert share_code_from_fingerprint(fp) == "1usJ_IrzKLDb15Rq"


@pytest.mark.asyncio
async def test_bootstrap_share_code_commits_to_all_keys(client: AsyncClient):
    user = await bootstrap_user(client, pqc=True, label="fp")
    expected = share_code_from_fingerprint(compute_fingerprint(
        user.identity_key_b64, user.ed25519_pub_b64, user.pqc_ek_b64, user.mldsa_public_b64,
    ))
    assert user.share_code == expected


@pytest.mark.asyncio
async def test_rotation_without_endorsement_rejected(client: AsyncClient, alice: User):
    new_pk, new_pub = _new_ed25519()
    resp = await client.put(
        "/v1/identity/me/keys",
        json=_rotation_body(new_pk, new_pub, _b64(b"new_identity_without_endorsement")),
        headers=alice.auth_header(),
    )
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_rotation_endorsed_by_wrong_key_rejected(client: AsyncClient, alice: User):
    new_pk, new_pub = _new_ed25519()
    body = alice.endorse(_rotation_body(new_pk, new_pub, _b64(b"new_identity_wrong_signer")))
    # Re-sign the endorsement with the NEW key instead of the old one
    old_fp = bytes.fromhex(body["rotation_prev_fingerprint"])
    new_fp = compute_fingerprint(body["public_identity_key"], body["public_signing_key"], "", "")
    body["rotation_signature"] = _b64(new_pk.sign(rotation_payload(alice.user_id, old_fp, new_fp)))
    resp = await client.put("/v1/identity/me/keys", json=body, headers=alice.auth_header())
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_rotation_endorsement_must_reference_current_keys(client: AsyncClient, alice: User):
    new_pk, new_pub = _new_ed25519()
    body = alice.endorse(_rotation_body(new_pk, new_pub, _b64(b"new_identity_stale_prev")))
    body["rotation_prev_fingerprint"] = "00" * 32
    resp = await client.put("/v1/identity/me/keys", json=body, headers=alice.auth_header())
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_pqc_rotation_requires_mldsa_endorsement(client: AsyncClient, alice_pqc: User):
    from pqcrypto.sign.ml_dsa_65 import generate_keypair, sign

    new_pk, new_pub = _new_ed25519()
    new_dsa_pub, new_dsa_priv = generate_keypair()
    new_dsa_b64 = _b64(new_dsa_pub)
    body = _rotation_body(new_pk, new_pub, _b64(b"pqc_new_identity_key"))
    body["pqc_signing_key"] = new_dsa_b64
    body["pqc_key_proof"] = _b64(sign(new_dsa_priv, new_dsa_b64.encode("utf-8")))
    body = alice_pqc.endorse(body)

    missing = dict(body, rotation_pqc_signature="")
    resp = await client.put("/v1/identity/me/keys", json=missing, headers=alice_pqc.auth_header())
    assert resp.status_code == 400

    bogus = dict(body, rotation_pqc_signature=_b64(b"\x00" * 3309))
    resp = await client.put("/v1/identity/me/keys", json=bogus, headers=alice_pqc.auth_header())
    assert resp.status_code == 400

    resp = await client.put("/v1/identity/me/keys", json=body, headers=alice_pqc.auth_header())
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_bundle_publishes_verifiable_endorsement(client: AsyncClient, alice: User):
    old_fp = compute_fingerprint(alice.identity_key_b64, alice.ed25519_pub_b64, "", "")
    old_pub = Ed25519PublicKey.from_public_bytes(base64.b64decode(alice.ed25519_pub_b64))

    new_pk, new_pub = _new_ed25519()
    new_id = _b64(b"endorsed_new_identity_key")
    resp = await client.put(
        "/v1/identity/me/keys",
        json=alice.endorse(_rotation_body(new_pk, new_pub, new_id)),
        headers=alice.auth_header(),
    )
    assert resp.status_code == 200

    bundle = (await client.get(f"/v1/identity/{alice.user_id}/keys")).json()
    assert bundle["rotation_prev_fingerprint"] == old_fp.hex()
    new_fp = compute_fingerprint(
        bundle["public_identity_key"], bundle["public_signing_key"],
        bundle["pqc_encapsulation_key"], bundle["pqc_signing_key"],
    )
    # What a client holding the OLD keys checks before accepting the new ones:
    old_pub.verify(
        base64.b64decode(bundle["rotation_signature"]),
        rotation_payload(alice.user_id, old_fp, new_fp),
    )
    assert resp.json()["share_code"] == share_code_from_fingerprint(new_fp)


@pytest.mark.asyncio
async def test_auth_rejects_invalid_mldsa_signature(client: AsyncClient, alice_pqc: User):
    """Regression: an invalid ML-DSA token signature must not be accepted."""
    ts = str(int(time.time() * 1000))
    payload = f"{alice_pqc.user_id}.{ts}"
    ed_sig = _b64(alice_pqc.ed25519_private.sign(payload.encode("utf-8")))
    bogus_pqc = _b64(b"\x00" * 3309)
    resp = await client.get(
        "/v1/messages/pending",
        headers={"Authorization": f"Bearer {payload}.{ed_sig}.{bogus_pqc}"},
    )
    assert resp.status_code == 401

    # A genuine dual-signed token still works
    resp = await client.get("/v1/messages/pending", headers=alice_pqc.auth_header())
    assert resp.status_code == 200
