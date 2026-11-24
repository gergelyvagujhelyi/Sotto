"""Each account's latest app version, which apps 0.7.24+ send with every request.

Revision ID: 012
Revises: 011
Create Date: 2026-11-24
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "012"
down_revision: Union[str, None] = "011"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("identities", sa.Column("app_version", sa.String(32), nullable=True))
    op.add_column("identities", sa.Column("app_version_at", sa.DateTime(), nullable=True))


def downgrade() -> None:
    op.drop_column("identities", "app_version_at")
    op.drop_column("identities", "app_version")
