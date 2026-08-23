FROM ghcr.io/astral-sh/uv:0.11.28 AS uv

FROM python:3.13.14-slim-trixie

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

WORKDIR /app

COPY --from=uv /uv /uvx /bin/

COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project

COPY src/ src/
COPY migrations/ migrations/
COPY alembic.ini ./
RUN uv sync --locked --no-dev

RUN groupadd --system --gid 10001 postmark \
    && useradd --system --uid 10001 --gid 10001 --no-create-home postmark \
    && mkdir -p /var/lib/postmark/blobs \
    && chown 10001:10001 /var/lib/postmark/blobs

USER 10001:10001

EXPOSE 8000

CMD ["/app/.venv/bin/uvicorn", "postmark.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]
