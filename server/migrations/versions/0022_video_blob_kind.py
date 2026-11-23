"""ADR-021: a VIDEO artifact (the video square), at most one per capsule, ordinal NULL."""

import sqlalchemy as sa
from alembic import op


revision = "0022_video_blob_kind"
down_revision = "0021_media_blob_kind"
branch_labels = None
depends_on = None


_SHAPE_CHECK = "ck_capsule_blobs_kind_ordinal_shape"
_OLD_SHAPE_SQL = (
    "((kind = 'PHOTO' AND ordinal IS NOT NULL AND ordinal BETWEEN 0 AND 4) OR "
    "(kind IN ('RECOGNITION_MANIFEST', 'CONTENT_MANIFEST', 'MEDIA') AND ordinal IS NULL))"
)
_NEW_SHAPE_SQL = (
    "((kind = 'PHOTO' AND ordinal IS NOT NULL AND ordinal BETWEEN 0 AND 4) OR "
    "(kind IN ('RECOGNITION_MANIFEST', 'CONTENT_MANIFEST', 'MEDIA', 'VIDEO') AND ordinal IS NULL))"
)
_ONE_VIDEO = "uq_capsule_blobs_one_video_per_capsule"
# Partial indexes whose predicates name the enum; they must not survive the type swap.
_KIND_INDEXES = (
    ("uq_capsule_blobs_one_recognition_manifest_per_capsule", ["capsule_id"], "kind = 'RECOGNITION_MANIFEST'"),
    ("uq_capsule_blobs_one_content_manifest_per_capsule", ["capsule_id"], "kind = 'CONTENT_MANIFEST'"),
    ("uq_capsule_blobs_one_media_per_capsule", ["capsule_id"], "kind = 'MEDIA'"),
    ("uq_capsule_blobs_photo_ordinal_per_capsule", ["capsule_id", "ordinal"], "kind = 'PHOTO'"),
)


def upgrade() -> None:
    # As in 0005 and 0021: the new label must commit before a constraint may name it.
    with op.get_context().autocommit_block():
        op.execute(sa.text("ALTER TYPE capsule_blob_kind ADD VALUE IF NOT EXISTS 'VIDEO'"))
    op.drop_constraint(_SHAPE_CHECK, "capsule_blobs", type_="check")
    op.create_check_constraint(_SHAPE_CHECK, "capsule_blobs", _NEW_SHAPE_SQL)
    op.create_index(
        _ONE_VIDEO, "capsule_blobs", ["capsule_id"], unique=True,
        postgresql_where=sa.text("kind = 'VIDEO'"),
    )


def downgrade() -> None:
    bind = op.get_bind()
    if bind.scalar(sa.text("SELECT EXISTS (SELECT 1 FROM capsule_blobs WHERE kind = 'VIDEO')")):
        raise RuntimeError("cannot downgrade the VIDEO blob kind while video rows exist")
    op.drop_index(_ONE_VIDEO, table_name="capsule_blobs")
    op.drop_constraint(_SHAPE_CHECK, "capsule_blobs", type_="check")
    for name, _columns, _where in _KIND_INDEXES:
        op.drop_index(name, table_name="capsule_blobs")
    op.execute(sa.text("ALTER TYPE capsule_blob_kind RENAME TO capsule_blob_kind_video"))
    op.execute(
        sa.text("CREATE TYPE capsule_blob_kind AS ENUM ('RECOGNITION_MANIFEST', 'CONTENT_MANIFEST', 'PHOTO', 'MEDIA')")
    )
    op.execute(
        sa.text(
            "ALTER TABLE capsule_blobs ALTER COLUMN kind TYPE capsule_blob_kind "
            "USING kind::text::capsule_blob_kind"
        )
    )
    op.execute(sa.text("DROP TYPE capsule_blob_kind_video"))
    op.create_check_constraint(_SHAPE_CHECK, "capsule_blobs", _OLD_SHAPE_SQL)
    for name, columns, where in _KIND_INDEXES:
        op.create_index(name, "capsule_blobs", columns, unique=True, postgresql_where=sa.text(where))
