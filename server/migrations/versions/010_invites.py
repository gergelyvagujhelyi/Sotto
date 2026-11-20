"""Invite-only sign-up: invitations, and whether the relay has been founded.

A member creates a one-time invite (only its SHA-256 is stored); a new account
must bring one, except the very first account on a fresh relay. relay_state
remembers that the first account exists, seeded from the accounts already here.

Revision ID: 010
Revises: 009
Create Date: 2026-11-19
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "010"
down_revision: Union[str, None] = "009"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "invites",
        sa.Column("code_hash", sa.String(64), primary_key=True),
        sa.Column("created_by", sa.String(32), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
        sa.Column("used_by", sa.String(32), nullable=True),
        sa.Column("used_at", sa.DateTime(), nullable=True),
    )
    op.create_index("ix_invites_created_by", "invites", ["created_by"])
    op.create_table(
        "relay_state",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("founded", sa.Boolean(), nullable=False),
    )
    # A relay that already has accounts is founded: from now on they come by invite.
    op.execute(sa.text(
        "INSERT INTO relay_state (id, founded) VALUES (1, (SELECT COUNT(*) > 0 FROM identities))"
    ))


def downgrade() -> None:
    op.drop_table("relay_state")
    op.drop_index("ix_invites_created_by", table_name="invites")
    op.drop_table("invites")
