"""SQLAlchemy declarative base. No tables."""

from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass
