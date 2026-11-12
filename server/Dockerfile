# Build from the repository root:  docker build -f server/Dockerfile -t cmfserver .
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    DATA_DIR=/data IMPORT_DIR=/import PORT=8080

WORKDIR /app
COPY server/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
 && useradd --system --uid 1000 --home /app cmf \
 && mkdir -p /data /import && chown cmf /data /import

COPY cmfexport ./cmfexport
COPY server/cmfserver ./cmfserver

USER cmf
VOLUME ["/data", "/import"]
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=5s CMD python -c "import urllib.request,os;urllib.request.urlopen(f'http://127.0.0.1:{os.environ[\"PORT\"]}/healthz',timeout=4)"
CMD ["python", "-m", "cmfserver.app"]
