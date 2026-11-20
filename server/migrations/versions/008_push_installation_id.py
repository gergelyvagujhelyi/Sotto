"""Firebase installation ID as a push target, next to the FCM token.

Apps from 0.5.5 register their Firebase installation ID (FID) instead of an
FCM registration token, which Google has deprecated. Older apps keep sending
tokens, so both columns stay; an identity has at most one of them set.

Revision ID: 008
Revises: 007
Create Date: 2026-11-19
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "008"
down_revision: Union[str, None] = "007"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("identities", sa.Column("fcm_fid", sa.Text, nullable=True))


def downgrade() -> None:
    op.drop_column("identities", "fcm_fid")
