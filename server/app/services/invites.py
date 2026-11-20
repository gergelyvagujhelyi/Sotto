"""Invite codes and the relay's founding: who may create an account.

A code is 12 symbols of Crockford's base32 (60 random bits), shown as
``K7QX-M2PA-9TRB``. People type and read them, so input ignores case, spaces
and dashes, and takes O for 0 and I or L for 1.
"""

import hashlib
import secrets
from datetime import datetime, timezone

from fastapi import HTTPException
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.db import Identity, Invite, RelayState

ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
CODE_LENGTH = 12


def utcnow() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def new_code() -> str:
    return "".join(secrets.choice(ALPHABET) for _ in range(CODE_LENGTH))


def display_code(code: str) -> str:
    return "-".join(code[i:i + 4] for i in range(0, len(code), 4))


def normalize_code(text: str) -> str:
    """The code as stored, from whatever was typed or pasted; "" if it can't be one."""
    cleaned = "".join(ch for ch in text.upper() if ch.isalnum())
    cleaned = cleaned.replace("O", "0").replace("I", "1").replace("L", "1")
    if len(cleaned) != CODE_LENGTH or any(ch not in ALPHABET for ch in cleaned):
        return ""
    return cleaned


def hash_code(code: str) -> str:
    return hashlib.sha256(code.encode()).hexdigest()


async def relay_founded(db: AsyncSession) -> bool:
    """Whether the relay's first account exists (relay_state, or any account when it has no row)."""
    state = await db.get(RelayState, 1)
    if state is not None:
        return bool(state.founded)
    return (await db.execute(select(Identity.user_id).limit(1))).first() is not None


async def claim_founding(db: AsyncSession) -> bool:
    """Mark the relay founded; True only for the account that founds it (its first)."""
    result = await db.execute(
        update(RelayState).where(RelayState.id == 1, RelayState.founded.is_(False)).values(founded=True)
    )
    if result.rowcount == 1:
        return True
    if await db.get(RelayState, 1) is not None:
        return False
    # No row yet (a relay created from the models, as in tests).
    has_accounts = (await db.execute(select(Identity.user_id).limit(1))).first() is not None
    try:
        async with db.begin_nested():
            db.add(RelayState(id=1, founded=True))
    except IntegrityError:
        return False  # another sign-up founded it just now
    return not has_accounts


async def redeem(db: AsyncSession, typed: str, user_id: str) -> None:
    """Use up the invite for the account being created, or refuse (403 with the reason).

    Runs in the transaction that creates the account: if that fails, the invite
    is not spent. The conditional update makes two sign-ups with one code safe.
    """
    code = normalize_code(typed or "")
    if not (typed or "").strip():
        raise HTTPException(status_code=403, detail="invite_required")
    if not code:
        raise HTTPException(status_code=403, detail="invite_invalid")
    code_hash = hash_code(code)
    now = utcnow()
    result = await db.execute(
        update(Invite)
        .where(Invite.code_hash == code_hash, Invite.used_at.is_(None), Invite.expires_at > now)
        .values(used_by=user_id, used_at=now)
    )
    if result.rowcount == 1:
        return
    invite = await db.get(Invite, code_hash)
    if invite is None:
        raise HTTPException(status_code=403, detail="invite_invalid")
    if invite.used_at is not None:
        raise HTTPException(status_code=403, detail="invite_used")
    raise HTTPException(status_code=403, detail="invite_expired")
