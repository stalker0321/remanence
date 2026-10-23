"""Align pending foreign keys with the model names and ON DELETE RESTRICT."""
from alembic import op

revision = "0019_pending_fk_names"
down_revision = "0018_atomic_registration"
branch_labels = None
depends_on = None

# (table, generated name, model name, column, referred table)
_FKS = (
    ("address_proof_attempts", "address_proof_attempts_account_user_id_fkey", "fk_proof_attempts_account", "account_user_id", "users"),
    ("claim_challenges", "claim_challenges_account_user_id_fkey", "fk_claim_challenges_account", "account_user_id", "users"),
    ("claim_challenges", "claim_challenges_bundle_id_fkey", "fk_claim_challenges_bundle", "bundle_id", "user_key_bundles"),
    ("delivery_address_bindings", "delivery_address_bindings_owner_user_id_fkey", "fk_address_bindings_owner_users", "owner_user_id", "users"),
    ("pending_claims", "pending_claims_account_user_id_fkey", "fk_pending_claims_account", "account_user_id", "users"),
    ("pending_claims", "pending_claims_bundle_id_fkey", "fk_pending_claims_bundle", "bundle_id", "user_key_bundles"),
    ("pending_claims", "pending_claims_delivery_id_fkey", "fk_pending_claims_delivery", "delivery_id", "pending_deliveries"),
    ("pending_deliveries", "pending_deliveries_sender_user_id_fkey", "fk_pending_deliveries_sender", "sender_user_id", "users"),
    ("pending_incoming", "pending_incoming_delivery_id_fkey", "fk_pending_incoming_delivery", "delivery_id", "pending_deliveries"),
    ("pending_send_routes", "pending_send_routes_sender_user_id_fkey", "fk_send_routes_sender_users", "sender_user_id", "users"),
)


def upgrade():
    for table, old, new, column, referred in _FKS:
        op.drop_constraint(old, table, type_="foreignkey")
        op.create_foreign_key(new, table, referred, [column], ["id"], ondelete="RESTRICT")


def downgrade():
    for table, old, new, column, referred in reversed(_FKS):
        op.drop_constraint(new, table, type_="foreignkey")
        op.create_foreign_key(old, table, referred, [column], ["id"])
