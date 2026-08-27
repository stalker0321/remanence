"""M2 capsule routing schema.

Revision ID: 0003_m2_capsule_routing
Revises: 0002_m1_accounts
Create Date: 2026-08-27

"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0003_m2_capsule_routing"
down_revision = "0002_m1_accounts"
branch_labels = None
depends_on = None

_CAPSULE_STATE = "capsule_state"
_CAPSULE_BLOB_KIND = "capsule_blob_kind"
_CAPSULE_BLOB_STATE = "capsule_blob_state"
_RECIPIENT_DELIVERY_STATUS = "recipient_delivery_status"


def upgrade() -> None:
    bind = op.get_bind()
    postgresql.ENUM(
        "DRAFT",
        "READY",
        "ABORTED",
        name=_CAPSULE_STATE,
        create_type=False,
    ).create(bind)
    postgresql.ENUM(
        "RECOGNITION_MANIFEST",
        "CONTENT_MANIFEST",
        "PHOTO",
        name=_CAPSULE_BLOB_KIND,
        create_type=False,
    ).create(bind)
    postgresql.ENUM(
        "DECLARED",
        "STORED",
        name=_CAPSULE_BLOB_STATE,
        create_type=False,
    ).create(bind)
    postgresql.ENUM(
        "AVAILABLE",
        "CIPHERTEXT_SYNCED",
        name=_RECIPIENT_DELIVERY_STATUS,
        create_type=False,
    ).create(bind)

    capsule_state = postgresql.ENUM(
        "DRAFT",
        "READY",
        "ABORTED",
        name=_CAPSULE_STATE,
        create_type=False,
    )
    capsule_blob_kind = postgresql.ENUM(
        "RECOGNITION_MANIFEST",
        "CONTENT_MANIFEST",
        "PHOTO",
        name=_CAPSULE_BLOB_KIND,
        create_type=False,
    )
    capsule_blob_state = postgresql.ENUM(
        "DECLARED",
        "STORED",
        name=_CAPSULE_BLOB_STATE,
        create_type=False,
    )
    recipient_delivery_status = postgresql.ENUM(
        "AVAILABLE",
        "CIPHERTEXT_SYNCED",
        name=_RECIPIENT_DELIVERY_STATUS,
        create_type=False,
    )

    op.create_table(
        "capsules",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("sender_user_id", sa.Uuid(), nullable=False),
        sa.Column("recipient_user_id", sa.Uuid(), nullable=False),
        sa.Column("sender_key_bundle_id", sa.Uuid(), nullable=False),
        sa.Column("recipient_key_bundle_id", sa.Uuid(), nullable=False),
        sa.Column("protocol_version", sa.SmallInteger(), nullable=False),
        sa.Column("state", capsule_state, nullable=False),
        sa.Column("signed_statement", sa.LargeBinary(), nullable=True),
        sa.Column("signed_statement_sha256", sa.LargeBinary(length=32), nullable=True),
        sa.Column("publish_signature", sa.LargeBinary(length=69), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("ready_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("draft_expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id", name="pk_capsules"),
        sa.ForeignKeyConstraint(
            ["sender_user_id"],
            ["users.id"],
            name="fk_capsules_sender_user_id_users",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["recipient_user_id"],
            ["users.id"],
            name="fk_capsules_recipient_user_id_users",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["sender_key_bundle_id"],
            ["user_key_bundles.id"],
            name="fk_capsules_sender_key_bundle_id_user_key_bundles",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["recipient_key_bundle_id"],
            ["user_key_bundles.id"],
            name="fk_capsules_recipient_key_bundle_id_user_key_bundles",
            ondelete="RESTRICT",
        ),
        sa.CheckConstraint(
            "protocol_version > 0",
            name="ck_capsules_protocol_version_positive",
        ),
        sa.CheckConstraint(
            "draft_expires_at > created_at",
            name="ck_capsules_draft_expiry_order",
        ),
        sa.CheckConstraint(
            "signed_statement_sha256 IS NULL OR octet_length(signed_statement_sha256) = 32",
            name="ck_capsules_signed_statement_sha256_32",
        ),
        sa.CheckConstraint(
            "publish_signature IS NULL OR octet_length(publish_signature) = 69",
            name="ck_capsules_publish_signature_69",
        ),
        sa.CheckConstraint(
            "((state = 'READY' AND ready_at IS NOT NULL AND signed_statement IS NOT NULL "
            "AND signed_statement_sha256 IS NOT NULL AND publish_signature IS NOT NULL) OR "
            "(state IN ('DRAFT', 'ABORTED') AND ready_at IS NULL "
            "AND signed_statement IS NULL AND signed_statement_sha256 IS NULL "
            "AND publish_signature IS NULL))",
            name="ck_capsules_state_finalization_shape",
        ),
    )
    op.create_index("ix_capsules_sender_user_id", "capsules", ["sender_user_id"])
    op.create_index("ix_capsules_recipient_user_id", "capsules", ["recipient_user_id"])
    op.create_index("ix_capsules_draft_expires_at", "capsules", ["draft_expires_at"])

    op.create_table(
        "capsule_blobs",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("capsule_id", sa.Uuid(), nullable=False),
        sa.Column("kind", capsule_blob_kind, nullable=False),
        sa.Column("ordinal", sa.SmallInteger(), nullable=True),
        sa.Column("object_key", sa.String(length=512), nullable=False),
        sa.Column("expected_ciphertext_size", sa.BigInteger(), nullable=False),
        sa.Column("expected_ciphertext_sha256", sa.LargeBinary(length=32), nullable=False),
        sa.Column("state", capsule_blob_state, nullable=False),
        sa.PrimaryKeyConstraint("id", name="pk_capsule_blobs"),
        sa.ForeignKeyConstraint(
            ["capsule_id"],
            ["capsules.id"],
            name="fk_capsule_blobs_capsule_id_capsules",
            ondelete="CASCADE",
        ),
        sa.UniqueConstraint("object_key", name="uq_capsule_blobs_object_key"),
        sa.CheckConstraint(
            "expected_ciphertext_size > 0",
            name="ck_capsule_blobs_expected_ciphertext_size_positive",
        ),
        sa.CheckConstraint(
            "octet_length(expected_ciphertext_sha256) = 32",
            name="ck_capsule_blobs_expected_ciphertext_sha256_32",
        ),
        sa.CheckConstraint(
            "((kind = 'PHOTO' AND ordinal IS NOT NULL AND ordinal BETWEEN 0 AND 4) OR "
            "(kind IN ('RECOGNITION_MANIFEST', 'CONTENT_MANIFEST') AND ordinal IS NULL))",
            name="ck_capsule_blobs_kind_ordinal_shape",
        ),
    )
    op.create_index("ix_capsule_blobs_capsule_id", "capsule_blobs", ["capsule_id"])
    op.create_index(
        "uq_capsule_blobs_one_recognition_manifest_per_capsule",
        "capsule_blobs",
        ["capsule_id"],
        unique=True,
        postgresql_where=sa.text("kind = 'RECOGNITION_MANIFEST'"),
    )
    op.create_index(
        "uq_capsule_blobs_one_content_manifest_per_capsule",
        "capsule_blobs",
        ["capsule_id"],
        unique=True,
        postgresql_where=sa.text("kind = 'CONTENT_MANIFEST'"),
    )
    op.create_index(
        "uq_capsule_blobs_photo_ordinal_per_capsule",
        "capsule_blobs",
        ["capsule_id", "ordinal"],
        unique=True,
        postgresql_where=sa.text("kind = 'PHOTO'"),
    )

    op.create_table(
        "capsule_envelopes",
        sa.Column("capsule_id", sa.Uuid(), nullable=False),
        sa.Column("recipient_user_id", sa.Uuid(), nullable=False),
        sa.Column("recipient_key_bundle_id", sa.Uuid(), nullable=False),
        sa.Column("ciphertext", sa.LargeBinary(), nullable=False),
        sa.Column("ciphertext_size", sa.Integer(), nullable=False),
        sa.Column("ciphertext_sha256", sa.LargeBinary(length=32), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("capsule_id", name="pk_capsule_envelopes"),
        sa.ForeignKeyConstraint(
            ["capsule_id"],
            ["capsules.id"],
            name="fk_capsule_envelopes_capsule_id_capsules",
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["recipient_user_id"],
            ["users.id"],
            name="fk_capsule_envelopes_recipient_user_id_users",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["recipient_key_bundle_id"],
            ["user_key_bundles.id"],
            name="fk_capsule_envelopes_recipient_key_bundle_id_user_key_bundles",
            ondelete="RESTRICT",
        ),
        sa.CheckConstraint(
            "ciphertext_size > 0 AND ciphertext_size <= 16384",
            name="ck_capsule_envelopes_ciphertext_size_bounds",
        ),
        sa.CheckConstraint(
            "octet_length(ciphertext) = ciphertext_size",
            name="ck_capsule_envelopes_ciphertext_size_matches",
        ),
        sa.CheckConstraint(
            "octet_length(ciphertext_sha256) = 32",
            name="ck_capsule_envelopes_ciphertext_sha256_32",
        ),
    )

    op.create_table(
        "recipient_delivery_state",
        sa.Column("recipient_user_id", sa.Uuid(), nullable=False),
        sa.Column("capsule_id", sa.Uuid(), nullable=False),
        sa.Column("state", recipient_delivery_status, nullable=False),
        sa.Column(
            "available_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("ciphertext_synced_at", sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint(
            "recipient_user_id",
            "capsule_id",
            name="pk_recipient_delivery_state",
        ),
        sa.ForeignKeyConstraint(
            ["recipient_user_id"],
            ["users.id"],
            name="fk_recipient_delivery_state_recipient_user_id_users",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["capsule_id"],
            ["capsules.id"],
            name="fk_recipient_delivery_state_capsule_id_capsules",
            ondelete="CASCADE",
        ),
        sa.CheckConstraint(
            "((state = 'AVAILABLE' AND ciphertext_synced_at IS NULL) OR "
            "(state = 'CIPHERTEXT_SYNCED' AND ciphertext_synced_at IS NOT NULL))",
            name="ck_recipient_delivery_state_state_timestamp_coherence",
        ),
    )

    op.create_table(
        "capsule_idempotency_records",
        sa.Column("owner_user_id", sa.Uuid(), nullable=False),
        sa.Column("method", sa.String(length=8), nullable=False),
        sa.Column("normalized_route", sa.String(length=512), nullable=False),
        sa.Column("idempotency_key", sa.Uuid(), nullable=False),
        sa.Column("request_sha256", sa.LargeBinary(length=32), nullable=False),
        sa.Column("response_status", sa.SmallInteger(), nullable=False),
        sa.Column("response_json", postgresql.JSONB(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint(
            "owner_user_id",
            "method",
            "normalized_route",
            "idempotency_key",
            name="pk_capsule_idempotency_records",
        ),
        sa.ForeignKeyConstraint(
            ["owner_user_id"],
            ["users.id"],
            name="fk_capsule_idempotency_records_owner_user_id_users",
            ondelete="CASCADE",
        ),
        sa.CheckConstraint(
            "method IN ('POST', 'PUT', 'PATCH', 'DELETE') AND method = upper(method)",
            name="ck_capsule_idempotency_records_method_uppercase",
        ),
        sa.CheckConstraint(
            "octet_length(request_sha256) = 32",
            name="ck_capsule_idempotency_records_request_sha256_32",
        ),
        sa.CheckConstraint(
            "response_status BETWEEN 200 AND 599",
            name="ck_capsule_idempotency_records_response_status_range",
        ),
        sa.CheckConstraint(
            "expires_at > created_at",
            name="ck_capsule_idempotency_records_expiry_order",
        ),
    )
    op.create_index(
        "ix_capsule_idempotency_records_expires_at",
        "capsule_idempotency_records",
        ["expires_at"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_capsule_idempotency_records_expires_at",
        table_name="capsule_idempotency_records",
    )
    op.drop_table("capsule_idempotency_records")
    op.drop_table("recipient_delivery_state")
    op.drop_table("capsule_envelopes")
    op.drop_index(
        "uq_capsule_blobs_photo_ordinal_per_capsule",
        table_name="capsule_blobs",
    )
    op.drop_index(
        "uq_capsule_blobs_one_content_manifest_per_capsule",
        table_name="capsule_blobs",
    )
    op.drop_index(
        "uq_capsule_blobs_one_recognition_manifest_per_capsule",
        table_name="capsule_blobs",
    )
    op.drop_index("ix_capsule_blobs_capsule_id", table_name="capsule_blobs")
    op.drop_table("capsule_blobs")
    op.drop_index("ix_capsules_draft_expires_at", table_name="capsules")
    op.drop_index("ix_capsules_recipient_user_id", table_name="capsules")
    op.drop_index("ix_capsules_sender_user_id", table_name="capsules")
    op.drop_table("capsules")

    bind = op.get_bind()
    postgresql.ENUM(name=_RECIPIENT_DELIVERY_STATUS, create_type=False).drop(bind)
    postgresql.ENUM(name=_CAPSULE_BLOB_STATE, create_type=False).drop(bind)
    postgresql.ENUM(name=_CAPSULE_BLOB_KIND, create_type=False).drop(bind)
    postgresql.ENUM(name=_CAPSULE_STATE, create_type=False).drop(bind)
