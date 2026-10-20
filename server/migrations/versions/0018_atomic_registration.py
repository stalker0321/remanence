"""Isolated pre-account setup and atomic-registration safety fence; no backfill."""
from alembic import op
import sqlalchemy as sa

revision = "0018_atomic_registration"
down_revision = "0017_send_access"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "registration_setup_policy",
        sa.Column("id", sa.SmallInteger(), primary_key=True),
        sa.Column("required", sa.Boolean(), nullable=False),
        sa.CheckConstraint("id = 1", name="ck_registration_setup_policy_singleton"),
    )
    op.execute("INSERT INTO registration_setup_policy(id, required) VALUES (1, false)")
    op.create_table(
        "registration_reservations",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("account_id", sa.Uuid(), nullable=False, unique=True),
        sa.Column("subject", sa.String(255), nullable=False),
        sa.Column("email", sa.String(320), nullable=False),
        sa.Column("is_current", sa.Boolean(), nullable=False),
        sa.Column("state", sa.String(12), nullable=False),
        sa.Column("epoch", sa.BigInteger(), nullable=False),
        sa.Column("setup_session_id", sa.Uuid(), nullable=False),
        sa.Column("setup_token_hash", sa.LargeBinary(32), unique=True),
        sa.Column("setup_expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("terminal_at", sa.DateTime(timezone=True)),
        sa.Column("restart_authorized", sa.Boolean(), nullable=False),
        sa.Column("restarted_into", sa.Uuid(), sa.ForeignKey("registration_reservations.id", ondelete="SET NULL")),
        sa.Column("accepted_operation_id", sa.Uuid()),
        sa.Column("accepted_bundle_id", sa.Uuid()),
        sa.Column("accepted_credential_id", sa.String(1366)),
        sa.Column("accepted_handle", sa.String(30)),
        sa.Column("accepted_grant_hash", sa.LargeBinary(32)),
        sa.Column("activated_at", sa.DateTime(timezone=True)),
        sa.Column("receipt_expires_at", sa.DateTime(timezone=True)),
        sa.CheckConstraint("state IN ('RESERVED','ACTIVE','CANCELLED','EXPIRED')", name="ck_registration_reservation_state"),
        sa.CheckConstraint("epoch > 0", name="ck_registration_reservation_epoch"),
    )
    op.create_index("uq_registration_current_subject", "registration_reservations", ["subject"], unique=True,
                    postgresql_where=sa.text("is_current"))
    op.create_table(
        "registration_setup_credentials",
        sa.Column("id", sa.String(1366), primary_key=True),
        sa.Column("reservation_id", sa.Uuid(), sa.ForeignKey("registration_reservations.id", ondelete="CASCADE"), nullable=False),
        sa.Column("public_key", sa.LargeBinary(), nullable=False),
        sa.Column("counter", sa.BigInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_registration_setup_credentials_reservation_id", "registration_setup_credentials", ["reservation_id"])
    op.create_table(
        "registration_setup_challenges",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("reservation_id", sa.Uuid(), sa.ForeignKey("registration_reservations.id", ondelete="CASCADE"), nullable=False),
        sa.Column("epoch", sa.BigInteger(), nullable=False),
        sa.Column("setup_session_id", sa.Uuid(), nullable=False),
        sa.Column("purpose", sa.String(12), nullable=False),
        sa.Column("credential_id", sa.String(1366)),
        sa.Column("challenge", sa.LargeBinary(32), nullable=False),
        sa.Column("secret_hash", sa.LargeBinary(32), unique=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True)),
        sa.CheckConstraint("purpose IN ('REGISTER','SETUP','FINAL')", name="ck_registration_challenge_purpose"),
    )
    op.create_index("ix_registration_setup_challenges_reservation_id", "registration_setup_challenges", ["reservation_id"])


def downgrade():
    # A down migration must not erase the durable no-create-first latch or
    # ambiguous outcomes. Any removal needs an independently approved data plan.
    raise RuntimeError("atomic registration downgrade requires a separately approved data plan")
