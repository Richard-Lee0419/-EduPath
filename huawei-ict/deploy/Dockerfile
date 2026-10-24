FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    EDUPATH_BRIDGE_MODE=fixture \
    EDUPATH_PORT=8765

WORKDIR /app
COPY bridge ./bridge
COPY web ./web
COPY VERSION ./

EXPOSE 8765
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8765/health', timeout=2)"

CMD ["python", "-m", "bridge.gateway", "--host", "0.0.0.0", "--port", "8765"]
