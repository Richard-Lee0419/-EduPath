# EduPath AI Agent Service

FastAPI service for profile extraction, evidence-grounded resource generation, tutoring, learning paths, quizzes, evaluation and safety review. The Java API is the public gateway; browsers should not call this service directly.

## Development

Use Python 3.11 or newer. From this directory:

~~~bash
python -m venv .venv
python -m pip install -e ".[dev,vector]"
python -m uvicorn app.main:app --reload --port 8000
python -m pytest
~~~

When no real model is configured, development tasks can use a clearly marked deterministic mode. Production requires a configured LLM provider, vector store and non-local embeddings; it must not silently substitute development results for model output. See the root .env.production.example and docs/production-deployment.md.

## Task boundaries

The service uses structured Agent hand-offs for profile, evidence retrieval, resource planning, generation, tutoring, evaluation and SafetyAgent review. Resource tasks expose task IDs, status, SSE events, cancellation and model runtime metadata. Retrieved evidence IDs are validated; unsafe or unsupported output must not be treated as successful content.

The bundled seed corpus is open-source material with a provenance manifest. Private courseware and production credentials are not included. For a new course, register authorized sources, check license and knowledge-point metadata, then build and evaluate the corpus before using it.

## Key endpoints

- GET /health and GET /runtime
- POST /profile/extract
- POST /resource/tasks and GET /tasks/{task_id}
- GET /tasks/{task_id}/stream and POST /tasks/{task_id}/cancel
- POST /kb/search and POST /kb/ingest
- POST /path/generate and POST /path/replan
- POST /tutor/chat
- POST /quiz/generate and POST /evaluation/analyze

For end-to-end setup, read the repository root README.md.
