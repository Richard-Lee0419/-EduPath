# EduPath Architecture

## Overview

EduPath uses a three-part Monorepo architecture:

```text
Frontend Web
  -> Java Backend API
    -> Python AI Agent Service
      -> LLM, RAG, vector store, safety checks
```

The front-end never calls the AI service directly. All public application APIs are owned by the Java back-end under `/api/*`.

## Front-End

The front-end is responsible for pages, routes, state, API calls, Markdown/resource rendering, learning path visualization, Agent progress display, and competition demo polish.

Technology baseline:

- Vue 3
- TypeScript
- Vite
- Vue Router
- Pinia
- Axios

## Back-End API

The Java back-end is responsible for authentication, courses, knowledge points, document upload metadata, resource records, learning paths, quiz records, evaluation records, task status, and unified REST/SSE APIs.

Technology baseline:

- Spring Boot 3
- Maven
- Java 17 target
- Spring Web
- Spring Validation
- Springdoc OpenAPI

## AI Agent Service

The Python AI service owns multi-agent orchestration, RAG retrieval, structured generation, tutoring, learning path planning, evaluation, and safety review.

Technology baseline:

- FastAPI
- Pydantic
- pytest
- YAML prompts
- Explicit agent/service/schema/rag module boundaries

## Data Flow

```text
Student requests generated resources
  -> Front-end POST /api/agent/resource-task
  -> Java back-end creates task and calls AI service
  -> AI service orchestrates agents and returns structured result
  -> Java back-end stores result and updates task status
  -> Front-end polls status or receives SSE progress
```

## Contract Rules

- Use the common `{ code, message, data }` response wrapper for public Java APIs.
- Return `task_id` for long-running AI tasks.
- Keep generated AI content structured enough for validation and front-end rendering.
- Update `api-contract.md` when endpoints or payloads change.
