# syntax=docker/dockerfile:1
ARG PYTHON_IMAGE=python:3.12-slim
FROM ${PYTHON_IMAGE}
RUN apt-get update && apt-get install -y --no-install-recommends poppler-utils && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY llm/requirements.txt ./llm/requirements.txt
COPY llm/classification/requirements.txt ./llm/classification/requirements.txt
COPY llm/estimation/requirements.txt ./llm/estimation/requirements.txt
RUN pip install --no-cache-dir -r llm/requirements.txt
COPY llm ./llm
COPY data/classification ./data/classification
COPY data/plans ./data/plans
COPY data/rag ./data/rag
COPY scripts/rag/build_reference_index.py ./scripts/rag/build_reference_index.py
COPY data/inspirations/analysis.schema.json data/inspirations/prompt.txt ./data/inspirations/
ENV PYTHONPATH=/app/llm:/app/llm/classification:/app/llm/estimation DATA_DIR=/app/data PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER 10001:10001
ENTRYPOINT ["python", "-m", "repaircalc_llm"]
