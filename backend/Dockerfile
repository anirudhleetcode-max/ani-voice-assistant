# Small image for running Ani's backend.
#
# The API key is passed at runtime via environment variables and is never baked into a
# layer — an image with a key in it is a key published to your registry.
FROM python:3.12-slim

WORKDIR /srv

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# Runs as a non-root user; nothing here needs privileges.
RUN useradd --create-home --uid 10001 ani
USER ani

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
