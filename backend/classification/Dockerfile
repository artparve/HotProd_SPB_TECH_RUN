FROM python:3.11-slim
WORKDIR /app
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY repaircalc_classifier ./repaircalc_classifier
USER 10001:10001
ENTRYPOINT ["python", "-m", "repaircalc_classifier"]
