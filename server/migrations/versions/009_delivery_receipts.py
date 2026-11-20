"""Delivery receipts, created by the relay when a recipient acks messages.

The relay is the one that knows a message reached the recipient's phone (it
deletes the envelope on ack), so it queues a receipt for the message's author.
Kept apart from read receipts, so apps up to 0.5.9 never see them.

Revision ID: 009
Revises: 008
Create Date: 2026-11-19
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "009"
down_revision: Union[str, None] = "008"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "delivery_receipts",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("sender_id", sa.String(32), nullable=False),
        sa.Column("recipient_id", sa.String(32), nullable=False),
        sa.Column("message_ids", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(), server_default=sa.func.now()),
    )
    op.create_index("ix_delivery_receipt_recipient", "delivery_receipts", ["recipient_id", "id"])


def downgrade() -> None:
    op.drop_index("ix_delivery_receipt_recipient", table_name="delivery_receipts")
    op.drop_table("delivery_receipts")
