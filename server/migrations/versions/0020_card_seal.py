"""First gift keyed by the card: find by verified phone, wipe on terminal states."""
import sqlalchemy as sa
from alembic import op

revision = "0020_card_seal"
down_revision = "0019_pending_fk_names"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("pending_deliveries", sa.Column("destination_lookup_tag", sa.LargeBinary(32), nullable=True))
    op.add_column("pending_deliveries", sa.Column("recipient_hint", sa.String(32), nullable=True))
    op.add_column("pending_deliveries", sa.Column("sender_code_sealed", sa.LargeBinary(), nullable=True))
    op.create_index(
        "ix_pending_deliveries_lookup_tag", "pending_deliveries", ["destination_lookup_tag"],
        postgresql_where=sa.text("state = 'PENDING'"),
    )
    op.add_column("claim_challenges", sa.Column("claim_id", sa.UUID(as_uuid=True), nullable=True))
    op.add_column("claim_challenges", sa.Column("claim_binding", sa.LargeBinary(), nullable=True))
    op.alter_column("pending_send_routes", "sealed_destination", existing_type=sa.LargeBinary(), nullable=True)
    op.create_index(
        "ix_proof_attempts_address_time", "address_proof_attempts", ["address_lookup_tag", "created_at"],
    )
    op.create_index("ix_proof_attempts_created", "address_proof_attempts", ["created_at"])


def downgrade():
    op.drop_index("ix_proof_attempts_created", table_name="address_proof_attempts")
    op.drop_index("ix_proof_attempts_address_time", table_name="address_proof_attempts")
    op.execute("UPDATE pending_send_routes SET sealed_destination = ''::bytea WHERE sealed_destination IS NULL")
    op.alter_column("pending_send_routes", "sealed_destination", existing_type=sa.LargeBinary(), nullable=False)
    op.drop_column("claim_challenges", "claim_binding")
    op.drop_column("claim_challenges", "claim_id")
    op.drop_index("ix_pending_deliveries_lookup_tag", table_name="pending_deliveries")
    op.drop_column("pending_deliveries", "sender_code_sealed")
    op.drop_column("pending_deliveries", "recipient_hint")
    op.drop_column("pending_deliveries", "destination_lookup_tag")
