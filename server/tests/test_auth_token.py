"""Auth tokens: the freshness window, and the ML-DSA signature a PQC account must add."""

import base64

import pytest
from pqcrypto.sign.ml_dsa_65 import sign

try:  # pqcrypto 1.x
    from pqcrypto.sign.ml_dsa_65 import keygen
except ImportError:  # 0.x, as pinned for the relay
    from pqcrypto.sign.ml_dsa_65 import generate_keypair as keygen

from app import config
from app.auth import TOKEN_MAX_AGE_MS, verify_token
from tests.auth_helpers import fresh_ts_ms, generate_ed25519_keypair


def _token(priv, user_id: str, ts_ms: int, mldsa_sk: bytes | None = None) -> str:
    payload = f"{user_id}.{ts_ms}"
    sig = base64.b64encode(priv.sign(payload.encode())).decode()
    token = f"{payload}.{sig}"
    if mldsa_sk is not None:
        token += "." + base64.b64encode(sign(mldsa_sk, payload.encode())).decode()
    return token


def _now() -> int:
    return fresh_ts_ms()


def test_window_is_five_minutes_each_way():
    priv, pub = generate_ed25519_keypair()
    assert TOKEN_MAX_AGE_MS == 5 * 60 * 1000
    assert verify_token(_token(priv, "u1", _now() - 4 * 60_000), pub) == "u1"
    assert verify_token(_token(priv, "u1", _now() + 4 * 60_000), pub) == "u1"
    with pytest.raises(ValueError, match="expired"):
        verify_token(_token(priv, "u1", _now() - 6 * 60_000), pub)
    with pytest.raises(ValueError, match="expired"):
        verify_token(_token(priv, "u1", _now() + 6 * 60_000), pub)


def test_pqc_account_without_mldsa_signature_is_refused():
    """A token without it is refused, so a failed ML-DSA signature can't downgrade the login."""
    priv, pub = generate_ed25519_keypair()
    mldsa_pk, mldsa_sk = keygen()
    pqc_pub = base64.b64encode(mldsa_pk).decode()
    assert verify_token(_token(priv, "u1", _now(), mldsa_sk), pub, pqc_pub) == "u1"
    with pytest.raises(ValueError, match="ML-DSA-65 signature required"):
        verify_token(_token(priv, "u1", _now()), pub, pqc_pub)


@pytest.fixture
def single_use_tokens(monkeypatch):
    monkeypatch.setattr(config, "AUTH_SINGLE_USE_TOKENS", True)


def test_replays_are_allowed_until_the_relay_turns_single_use_tokens_on():
    """The default, so a deploy doesn't lock out phones on older versions."""
    priv, pub = generate_ed25519_keypair()
    token = _token(priv, "u1", _now())
    assert verify_token(token, pub) == "u1"
    assert verify_token(token, pub) == "u1"


def test_a_token_is_refused_the_second_time(single_use_tokens):
    """A token copied off the wire (or out of a log) can't be used again inside its window."""
    priv, pub = generate_ed25519_keypair()
    token = _token(priv, "u1", _now())
    assert verify_token(token, pub) == "u1"
    with pytest.raises(ValueError, match="already used"):
        verify_token(token, pub)


def test_a_fresh_token_still_works_after_a_refused_replay(single_use_tokens):
    priv, pub = generate_ed25519_keypair()
    ts = _now()
    token = _token(priv, "u1", ts)
    verify_token(token, pub)
    with pytest.raises(ValueError):
        verify_token(token, pub)
    assert verify_token(_token(priv, "u1", ts + 1), pub) == "u1"
