"""T185 M2: nullable handles, address registry, frozen routes, locator tables."""
from alembic import op
import sqlalchemy as sa

revision = "0013_pending_foundation"
down_revision = "0012_anonymous_telemetry"
branch_labels = None
depends_on = None


def upgrade():
    op.alter_column("users", "handle_normalized", existing_type=sa.String(30), nullable=True)
    op.alter_column("users", "handle_display", existing_type=sa.String(30), nullable=True)
    op.create_check_constraint(
        "ck_users_handle_paired_null",
        "users",
        "((handle_normalized IS NULL AND handle_display IS NULL) OR "
        "(handle_normalized IS NOT NULL AND handle_display IS NOT NULL))",
    )
    op.create_table(
        "delivery_address_bindings",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("kind", sa.String(10), nullable=False),
        sa.Column("normalization_version", sa.SmallInteger(), nullable=False, server_default="1"),
        sa.Column("lookup_tag", sa.LargeBinary(32), nullable=False),
        sa.Column("owner_user_id", sa.Uuid(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("state", sa.String(10), nullable=False),
        sa.Column("binding_revision", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("evidence_method", sa.String(20), nullable=True),
        sa.Column("evidence_ref", sa.LargeBinary(32), nullable=True),
        sa.Column("verified_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint("kind IN ('email', 'phone')", name="ck_address_bindings_kind"),
        sa.CheckConstraint("state IN ('RESERVED', 'VERIFIED', 'RETIRED')", name="ck_address_bindings_state"),
        sa.CheckConstraint("binding_revision > 0", name="ck_address_bindings_revision_positive"),
        sa.CheckConstraint("octet_length(lookup_tag) = 32", name="ck_address_bindings_lookup_tag_32"),
        sa.CheckConstraint("(state <> 'VERIFIED' OR verified_at IS NOT NULL)", name="ck_address_bindings_verified_needs_time"),
    )
    op.create_index(
        "uq_address_bindings_live_owner",
        "delivery_address_bindings",
        ["kind", "lookup_tag"],
        unique=True,
        postgresql_where=sa.text("state <> 'RETIRED'"),
    )
    op.create_index("ix_address_bindings_owner", "delivery_address_bindings", ["owner_user_id"])
    op.create_table(
        "pending_send_routes",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("sender_user_id", sa.Uuid(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("capsule_id", sa.Uuid(), nullable=False),
        sa.Column("mode", sa.String(20), nullable=False),
        sa.Column("destination_kind", sa.String(10), nullable=False),
        sa.Column("sealed_destination", sa.LargeBinary(), nullable=False),
        sa.Column("target_user_id", sa.Uuid(), nullable=True),
        sa.Column("target_bundle_id", sa.Uuid(), nullable=True),
        sa.Column("delivery_target_id", sa.Uuid(), nullable=True),
        sa.Column("binding_revision", sa.Integer(), nullable=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint("mode IN ('EXISTING_USER_V1', 'PENDING_V2')", name="ck_send_routes_mode"),
        sa.CheckConstraint(
            "((mode = 'EXISTING_USER_V1' AND target_user_id IS NOT NULL "
            "AND target_bundle_id IS NOT NULL AND delivery_target_id IS NULL) OR "
            "(mode = 'PENDING_V2' AND delivery_target_id IS NOT NULL "
            "AND target_user_id IS NULL AND target_bundle_id IS NULL))",
            name="ck_send_routes_arm_coherence",
        ),
    )
    op.create_index("ix_send_routes_sender", "pending_send_routes", ["sender_user_id"])
    op.create_table(
        "gift_locators",
        sa.Column("id", sa.Uuid(), primary_key=True),
        # No FK to capsules: pending gifts share the common locator before
        # any v1 capsule row exists. One locator per capsule via unique.
        sa.Column("capsule_id", sa.Uuid(), nullable=False),
        sa.Column("code_hmac", sa.LargeBinary(32), nullable=False),
        sa.Column("code_ciphertext", sa.LargeBinary(), nullable=True),
        sa.Column("state", sa.String(20), nullable=False),
        sa.Column("hint_mask", sa.String(64), nullable=False),
        sa.Column("policy_version", sa.SmallInteger(), nullable=False, server_default="1"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("retired_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "state IN ('RESERVED', 'ACTIVE', 'RETIRED_OPEN', 'RETIRED_TERMINAL')",
            name="ck_gift_locators_state",
        ),
        sa.CheckConstraint("octet_length(code_hmac) = 32", name="ck_gift_locators_hmac_32"),
        sa.UniqueConstraint("capsule_id", name="uq_gift_locators_capsule"),
        sa.UniqueConstraint("code_hmac", name="uq_gift_locators_code_hmac"),
    )
    op.create_index("ix_gift_locators_state", "gift_locators", ["state"])
    op.create_table(
        "used_locator_tombstones",
        sa.Column("code_hmac", sa.LargeBinary(32), primary_key=True),
        sa.Column("retired_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.CheckConstraint("octet_length(code_hmac) = 32", name="ck_locator_tombstones_hmac_32"),
    )


def downgrade():
    connection = op.get_bind()
    handleless = connection.execute(
        sa.text("SELECT COUNT(*) FROM users WHERE handle_normalized IS NULL")
    ).scalar()
    if handleless:
        raise RuntimeError(
            "refuse downgrade: handleless receive-only accounts exist; "
            "assign handles before downgrading"
        )
    op.drop_table("used_locator_tombstones")
    op.drop_index("ix_gift_locators_state", table_name="gift_locators")
    op.drop_table("gift_locators")
    op.drop_index("ix_send_routes_sender", table_name="pending_send_routes")
    op.drop_table("pending_send_routes")
    op.drop_index("ix_address_bindings_owner", table_name="delivery_address_bindings")
    op.drop_index(
        "uq_address_bindings_live_owner",
        table_name="delivery_address_bindings",
        postgresql_where=sa.text("state <> 'RETIRED'"),
    )
    op.drop_table("delivery_address_bindings")
    op.drop_constraint("ck_users_handle_paired_null", "users", type_="check")
    op.alter_column("users", "handle_display", existing_type=sa.String(30), nullable=False)
    op.alter_column("users", "handle_normalized", existing_type=sa.String(30), nullable=False)
