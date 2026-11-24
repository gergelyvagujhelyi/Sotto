FROM python:3.12-slim AS base

WORKDIR /app

# Install dependencies first (layer cache)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

RUN addgroup --system app && adduser --system --ingroup app app

COPY --chown=app:app . .

ENV PYTHONPATH=/app

USER app

# Run Alembic migrations then start uvicorn. No access log: it would list every path, account
# ids and share codes among them, which nginx's own log leaves out on purpose.
EXPOSE 8000
CMD ["sh", "-c", "alembic upgrade head && exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --no-access-log"]
