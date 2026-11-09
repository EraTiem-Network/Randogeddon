FROM python:3.12-slim-bookworm
ENV PYTHONUNBUFFERED=1
WORKDIR /app
COPY pyproject.toml ./
COPY tracker ./tracker
RUN pip install --no-cache-dir . && useradd --system --uid 1000 tracker && mkdir -p /state && chown tracker /state
USER tracker
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8000/healthz')"
CMD ["ap-tracker"]
