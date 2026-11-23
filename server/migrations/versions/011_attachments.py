"""Encrypted attachments in an object store: uploads in flight, and each account's usage.

Revision ID: 011
Revises: 010
Create Date: 2026-11-23
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "011"
down_revision: Union[str, None] = "010"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "attachments",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("sender_id", sa.String(32), nullable=False),
        sa.Column("recipient_id", sa.String(32), nullable=False),
        sa.Column("size", sa.BigInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("uploaded_at", sa.DateTime(), nullable=True),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
        sa.Column("upload_id", sa.String(512), nullable=True),
    )
    op.create_index("ix_attachments_sender_id", "attachments", ["sender_id"])
    op.create_index("ix_attachments_recipient_id", "attachments", ["recipient_id"])
    op.create_index("ix_attachments_created_at", "attachments", ["created_at"])
    op.create_index("ix_attachments_expires_at", "attachments", ["expires_at"])
    op.create_table(
        "attachment_usage",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("user_id", sa.String(32), nullable=False),
        sa.Column("size", sa.BigInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_attachment_usage_user_id", "attachment_usage", ["user_id"])
    op.create_index("ix_attachment_usage_created_at", "attachment_usage", ["created_at"])


def downgrade() -> None:
    op.drop_table("attachment_usage")
    op.drop_table("attachments")
