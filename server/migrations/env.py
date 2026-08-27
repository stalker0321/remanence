from alembic import context
from sqlalchemy import create_engine, pool

from remanence.auth.models import AuthCredential as _AuthCredential
from remanence.auth.models import AuthSession as _AuthSession
from remanence.capsules.blob_models import CapsuleBlob as _CapsuleBlob
from remanence.capsules.delivery_models import RecipientDeliveryState as _RecipientDeliveryState
from remanence.capsules.envelope_models import CapsuleEnvelope as _CapsuleEnvelope
from remanence.capsules.idempotency_models import CapsuleIdempotencyRecord as _CapsuleIdempotencyRecord
from remanence.capsules.models import Capsule as _Capsule
from remanence.db.base import Base
from remanence.db.session import DatabaseConfigurationError
from remanence.settings import Settings
from remanence.users.key_models import UserKeyBundle as _UserKeyBundle
from remanence.users.models import User as _User

target_metadata = Base.metadata


def _database_url() -> str:
    settings = Settings()
    if settings.database_url is None:
        raise DatabaseConfigurationError("database_url is required")
    return settings.database_url.get_secret_value()


def run_migrations_offline() -> None:
    context.configure(
        url=_database_url(),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        compare_type=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    engine = create_engine(_database_url(), poolclass=pool.NullPool)
    try:
        with engine.connect() as connection:
            context.configure(
                connection=connection,
                target_metadata=target_metadata,
                compare_type=True,
            )
            with context.begin_transaction():
                context.run_migrations()
    finally:
        engine.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
