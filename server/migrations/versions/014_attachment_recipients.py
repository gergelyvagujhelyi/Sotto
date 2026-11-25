"""An attachment may have several recipients (a file sent to a group goes up once).

Every existing attachment gets a row for its one recipient, so nothing changes for them.

Revision ID: 014
Revises: 013
Create Date: 2026-11-25
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "014"
down_revision: Union[str, None] = "013"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "attachment_recipients",
        sa.Column("attachment_id", sa.String(32), primary_key=True),
        sa.Column("recipient_id", sa.String(32), primary_key=True),
    )
    op.create_index("ix_attachment_recipients_recipient_id", "attachment_recipients", ["recipient_id"])
    op.execute(
        "INSERT INTO attachment_recipients (attachment_id, recipient_id) "
        "SELECT id, recipient_id FROM attachments"
    )


def downgrade() -> None:
    op.drop_table("attachment_recipients")
