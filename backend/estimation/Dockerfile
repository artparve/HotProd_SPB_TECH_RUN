FROM python:3.11-slim
WORKDIR /app
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
COPY requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY repaircalc_estimator ./repaircalc_estimator
USER 10001:10001
EXPOSE 8010
ENTRYPOINT ["python", "-m", "repaircalc_estimator"]
