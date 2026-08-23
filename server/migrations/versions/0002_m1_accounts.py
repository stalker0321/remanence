"""M1 accounts schema: users, auth credentials, auth sessions, user key bundles.

Revision ID: 0002_m1_accounts
Revises: 0001_m0_baseline
Create Date: 2026-08-23

"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0002_m1_accounts"
down_revision = "0001_m0_baseline"
branch_labels = None
depends_on = None

_KEY_BUNDLE_STATUS = "key_bundle_status"


def upgrade() -> None:
    key_bundle_status = postgresql.ENUM(
        "ACTIVE",
        "RETIRED",
        "REVOKED",
        name=_KEY_BUNDLE_STATUS,
        create_type=False,
    )
    key_bundle_status.create(op.get_bind())

    op.create_table(
        "users",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("email_normalized", sa.String(length=320), nullable=False),
        sa.Column("handle_normalized", sa.String(length=30), nullable=False),
        sa.Column("handle_display", sa.String(length=30), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("disabled_at", sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint("id", name="pk_users"),
        sa.UniqueConstraint("email_normalized", name="uq_users_email_normalized"),
        sa.UniqueConstraint("handle_normalized", name="uq_users_handle_normalized"),
    )

    op.create_table(
        "auth_credentials",
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("password_hash", sa.String(length=512), nullable=False),
        sa.Column(
            "password_changed_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("user_id", name="pk_auth_credentials"),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name="fk_auth_credentials_user_id_users",
            ondelete="CASCADE",
        ),
    )

    op.create_table(
        "auth_sessions",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("lineage_id", sa.Uuid(), nullable=False),
        sa.Column("parent_session_id", sa.Uuid(), nullable=True),
        sa.Column("access_token_hash", sa.LargeBinary(length=32), nullable=False),
        sa.Column("refresh_token_hash", sa.LargeBinary(length=32), nullable=False),
        sa.Column("access_expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("refresh_expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("last_used_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("rotated_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint("id", name="pk_auth_sessions"),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name="fk_auth_sessions_user_id_users",
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["parent_session_id"],
            ["auth_sessions.id"],
            name="fk_auth_sessions_parent_session_id_auth_sessions",
            ondelete="RESTRICT",
        ),
        sa.UniqueConstraint("access_token_hash", name="uq_auth_sessions_access_token_hash"),
        sa.UniqueConstraint("refresh_token_hash", name="uq_auth_sessions_refresh_token_hash"),
        sa.UniqueConstraint("parent_session_id", name="uq_auth_sessions_parent_session_id"),
        sa.CheckConstraint(
            "octet_length(access_token_hash) = 32",
            name="ck_auth_sessions_access_token_hash_32",
        ),
        sa.CheckConstraint(
            "octet_length(refresh_token_hash) = 32",
            name="ck_auth_sessions_refresh_token_hash_32",
        ),
        sa.CheckConstraint(
            "refresh_expires_at > access_expires_at",
            name="ck_auth_sessions_expiry_order",
        ),
    )
    op.create_index("ix_auth_sessions_user_id", "auth_sessions", ["user_id"])
    op.create_index("ix_auth_sessions_lineage_id", "auth_sessions", ["lineage_id"])

    op.create_table(
        "user_key_bundles",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("encryption_public_keyset", sa.LargeBinary(), nullable=False),
        sa.Column("signing_public_keyset", sa.LargeBinary(), nullable=False),
        sa.Column("suite", sa.String(length=80), nullable=False),
        sa.Column("protocol_version", sa.SmallInteger(), nullable=False),
        sa.Column("status", key_bundle_status, nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("retired_at", sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint("id", name="pk_user_key_bundles"),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name="fk_user_key_bundles_user_id_users",
            ondelete="CASCADE",
        ),
        sa.CheckConstraint(
            "protocol_version > 0",
            name="ck_user_key_bundles_protocol_version_positive",
        ),
    )
    op.create_index(
        "uq_user_key_bundles_one_active_per_user",
        "user_key_bundles",
        ["user_id"],
        unique=True,
        postgresql_where=sa.text("status = 'ACTIVE'"),
    )


def downgrade() -> None:
    op.drop_index("uq_user_key_bundles_one_active_per_user", table_name="user_key_bundles")
    op.drop_index("ix_auth_sessions_lineage_id", table_name="auth_sessions")
    op.drop_index("ix_auth_sessions_user_id", table_name="auth_sessions")
    op.drop_table("user_key_bundles")
    op.drop_table("auth_sessions")
    op.drop_table("auth_credentials")
    op.drop_table("users")
    postgresql.ENUM(name=_KEY_BUNDLE_STATUS, create_type=False).drop(op.get_bind())
