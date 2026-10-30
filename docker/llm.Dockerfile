# syntax=docker/dockerfile:1
ARG PYTHON_IMAGE=python:3.12-slim
FROM ${PYTHON_IMAGE}
WORKDIR /app
COPY llm/requirements.txt ./llm/requirements.txt
COPY llm/classification/requirements.txt ./llm/classification/requirements.txt
COPY llm/estimation/requirements.txt ./llm/estimation/requirements.txt
RUN pip install --no-cache-dir -r llm/requirements.txt
COPY llm ./llm
COPY data/classification ./data/classification
COPY data/plans ./data/plans
COPY data/inspirations/analysis.schema.json data/inspirations/prompt.txt ./data/inspirations/
ENV PYTHONPATH=/app/llm:/app/llm/classification:/app/llm/estimation DATA_DIR=/app/data PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER 10001:10001
ENTRYPOINT ["python", "-m", "repaircalc_llm"]
