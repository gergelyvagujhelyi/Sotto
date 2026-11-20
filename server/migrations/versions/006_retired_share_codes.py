"""Retired share codes: codes of rotated-away or deleted key sets.

Revision ID: 006
Revises: 005
Create Date: 2026-11-18
"""
import base64
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "006"
down_revision: Union[str, None] = "005"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _share_code_from_fingerprint(fingerprint_hex: str) -> str:
    # Frozen copy of app.routers.identity.share_code_from_fingerprint.
    return base64.urlsafe_b64encode(bytes.fromhex(fingerprint_hex)[:12]).decode().rstrip("=")


def upgrade() -> None:
    table = op.create_table(
        "retired_share_codes",
        sa.Column("share_code", sa.String(24), primary_key=True),
        sa.Column("retired_at", sa.DateTime(), server_default=sa.func.now()),
    )
    # Codes rotated away before this table existed: each identity remembers the
    # fingerprint of the key set it replaced last.
    rows = op.get_bind().execute(sa.text(
        "SELECT rotation_prev_fingerprint FROM identities WHERE rotation_prev_fingerprint != ''"
    )).fetchall()
    codes = set()
    for (fp,) in rows:
        try:
            codes.add(_share_code_from_fingerprint(fp))
        except ValueError:
            continue
    if codes:
        op.bulk_insert(table, [{"share_code": c} for c in sorted(codes)])


def downgrade() -> None:
    op.drop_table("retired_share_codes")
