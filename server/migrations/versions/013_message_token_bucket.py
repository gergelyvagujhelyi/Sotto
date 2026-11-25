"""Message sends are limited by a token bucket per account instead of a fixed window.

The old columns stay (unread) so a downgrade or an older relay still finds them.

Revision ID: 013
Revises: 012
Create Date: 2026-11-25
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "013"
down_revision: Union[str, None] = "012"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("rate_limits", sa.Column("tokens", sa.Float(), nullable=True))
    op.add_column("rate_limits", sa.Column("refilled_at", sa.DateTime(), nullable=True))


def downgrade() -> None:
    op.drop_column("rate_limits", "refilled_at")
    op.drop_column("rate_limits", "tokens")
