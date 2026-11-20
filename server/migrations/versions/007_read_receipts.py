"""Read receipts, kept apart from pending messages.

Revision ID: 007
Revises: 006
Create Date: 2026-11-18
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "007"
down_revision: Union[str, None] = "006"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "read_receipts",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("sender_id", sa.String(32), nullable=False),
        sa.Column("recipient_id", sa.String(32), nullable=False),
        sa.Column("message_ids", sa.Text(), nullable=False),
        sa.Column("signature", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(), server_default=sa.func.now()),
    )
    op.create_index("ix_receipt_recipient", "read_receipts", ["recipient_id", "id"])


def downgrade() -> None:
    op.drop_index("ix_receipt_recipient", table_name="read_receipts")
    op.drop_table("read_receipts")
