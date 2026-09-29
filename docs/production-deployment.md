# EduPath Production Deployment

This document describes the production-ready shape of the monorepo. The public entrypoint is the front-end Nginx container. Browser traffic reaches Spring Boot through `/api/*`; the Python AI service is internal only.

## Services

| Service | Image | Network exposure |
|---|---|---|
| `frontend` | Vue build served by Nginx | Public `${FRONTEND_PORT:-80}` |
| `backend-api` | Spring Boot JAR | Internal `8080` |
| `ai-agent-service` | FastAPI multi-agent/RAG service | Internal `8000` |
| `mysql` | MySQL 8.4, unless external DB override is used | Internal only |
| `chroma` | Chroma vector store | Internal only |

## First Deploy

```bash
cp .env.production.example .env.production
```

Replace every `CHANGE_ME` value in `.env.production`. Production startup intentionally fails if required secrets or AI/RAG settings are missing.

Email registration uses QQ Mail SMTP when `EMAIL_ENABLED=true`. Configure `MAIL_USERNAME` as the sender mailbox and `MAIL_PASSWORD` as the QQ SMTP authorization code; `MAIL_FROM` may be left empty to reuse `MAIL_USERNAME`.

```bash
./scripts/verify-production.sh
docker compose -f docker-compose.prod.yml --env-file .env.production build
docker compose -f docker-compose.prod.yml --env-file .env.production up -d
```

If the server already has a managed or system MySQL instance, create the database and user there, set `EXTERNAL_DB_URL` or `EXTERNAL_DB_PORT`, and start with the override instead:

```bash
sudo apt-get install -y socat
sudo ./scripts/setup-system-mysql-bridge.sh /opt/edupath
docker compose -f docker-compose.prod.yml -f docker-compose.external-db.yml --env-file .env.production up -d
```

`deploy/nginx-edupath.conf` is a host Nginx reverse-proxy template for servers that publish the front-end container on `127.0.0.1:18081` and expose public traffic through system Nginx on port 80.

Open `http://<host>/`. The default public route is same-origin `/api`, so no browser-side API host rewrite is required.

## AI App-Only Incremental Release

On a small server, rebuilding the AI image can spend a long time downloading and unpacking the CPU PyTorch runtime. When a release changes only `ai-agent-service/app/` and leaves `pyproject.toml`, the main `Dockerfile`, and the base image contract unchanged, reuse the previously verified image:

```bash
docker build \
  -f ai-agent-service/Dockerfile.incremental \
  --build-arg BASE_IMAGE=edupath/ai-agent-service:<previous-tag> \
  -t edupath/ai-agent-service:<new-tag> \
  ai-agent-service
```

The incremental Dockerfile replaces the complete `/app/app` package, so removed Python modules are not retained. Do not use this path after dependency, Python runtime, `data/`, or main Dockerfile changes; perform the full production build in that case. Keep the previous tagged image until the new AI container passes `/health`, `/readiness`, and a representative generation smoke test.

## Production Guardrails

- Back-end must run with `SPRING_PROFILES_ACTIVE=prod`.
- AI service must run with `EDUPATH_ENV=prod`.
- Flyway migration versions that have reached a deployed database are immutable. The production lineage keeps `V6__demo_class_readiness.sql`; later migrations continue from V7 through V16. Never reuse or renumber an applied version, because production startup validates the recorded description and checksum before applying new migrations.
- Back-end production startup rejects missing DB credentials, weak/default JWT secrets, missing AI service URL, default storage signing secrets, and non-OSS object storage.
- When registration email is enabled, back-end production startup also requires QQ SMTP sender credentials.
- AI production startup requires real LLM settings, Chroma vector store, non-local embeddings, and `EMBEDDING_STRICT=true`.
- AI embedding model files are cached in a Docker volume. For servers that cannot reach Hugging Face directly, set `HF_ENDPOINT` in `.env.production`.
- For servers with slow dependency registry access, set `MAVEN_MIRROR_URL`, `NPM_CONFIG_REGISTRY`, and `PIP_INDEX_URL` before building images. CPU-only PyTorch is selected through `TORCH_VERSION` and `TORCH_EXTRA_INDEX_URL`.
- `MYSQL_PLATFORM` defaults to `linux/amd64` for common ECS hosts; adjust it only when deploying to a different CPU architecture.
- `docker-compose.external-db.yml` moves `backend-api` to `host.docker.internal` and keeps the bundled MySQL service behind the inactive `compose-db` profile.
- On small CPU-only servers, use a compact embedding model such as `BAAI/bge-small-zh-v1.5`; larger models such as `BAAI/bge-m3` need more memory and slower first-run downloads.
- On small servers, initial embedding model loading and seed-corpus indexing can take several minutes before FastAPI binds its health endpoint. Keep `AI_HEALTH_START_PERIOD=300s` or increase it when the first startup exceeds the default window.
- The production compose file does not mount source code and does not inject a shared env file into every container; each service receives only the variables it needs.
- Only the front-end container publishes a host port. Back-end, AI service, MySQL, and Chroma stay on the internal Docker network.

## Verification

```bash
./scripts/production-readiness-check.sh
./scripts/verify-production.sh
```

`verify-production.sh` runs the production artifact check, front-end real API guard, front-end build, back-end tests, AI tests when the local venv exists, and `docker compose config` for `docker-compose.prod.yml`.
