# Команды повторной проверки

Из корня репозитория. Все команды выполнялись последовательно относительно реальных
LLM-запросов. Для Linux Docker Desktop; локальный override и env/.env не входят в Git.

```bash
make config
make up
python3 reports/deployment/2026-10-29/resource-limits/check-limits.py

docker compose --env-file env/.env -f docker/docker-compose.yml -f docker/docker-compose.override.yml exec -T estimator python - < reports/deployment/2026-10-29/smoke.py
```

Python-тесты (включая localhost HTTP-тесты внутри network=none):

```bash
docker run --rm --cpus=1 --memory=384m --memory-swap=384m --network=none -v "$PWD/backend/estimation:/app:ro" --entrypoint python repaircalc-estimator:local -m unittest discover -s tests -v
docker run --rm --cpus=1 --memory=384m --memory-swap=384m --network=none -v "$PWD/backend/classification:/app:ro" --entrypoint python repaircalc-classifier:local -m unittest discover -s tests -v
```

Frontend unit/contract (образ build-stage) и desktop/mobile E2E (системный Chrome,
один worker, CPU affinity 0–1; E2E работает с деморежимом Vite):

```bash
docker build --target build --build-arg VITE_DATA_MODE=api -t repaircalc-frontend-tests:local frontend
docker run --rm --cpus=1.5 --memory=1024m --memory-swap=1024m --network=none -v "$PWD/docs:/docs:ro" --entrypoint npm repaircalc-frontend-tests:local test -- --maxWorkers=1
cd frontend
CI=1 taskset -c 0,1 ./node_modules/.bin/playwright test --config=../reports/deployment/2026-10-29/resource-limits/playwright.config.mts --workers=1
cd ..
```

Java unit/API + PostgreSQL IT в отдельных контейнерах Testcontainers (не production БД).
Docker socket нужен самому Testcontainers. Данные fixtures смонтированы отдельно:

```bash
docker build --target build -t repaircalc-backend-tests:local backend
docker run --rm --cpus=2 --memory=1536m --memory-swap=1536m -e 'JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 -Xmx512m' -e DOCKER_HOST=unix:///var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD/backend/data:/build/data:ro" --add-host host.docker.internal:host-gateway repaircalc-backend-tests:local mvn -B verify
```

Два реальных LLM-сценария — только последовательно. Первый вызывает модель дважды
(tool-call, затем пояснение), второй классифицирует ровно одну карточку.

```bash
docker compose --env-file env/.env -f docker/docker-compose.yml -f docker/docker-compose.override.yml exec -T estimator python - < reports/deployment/2026-10-29/resource-limits/estimate-smoke.py
docker compose --env-file env/.env -f docker/docker-compose.yml -f docker/docker-compose.override.yml run --rm classifier --host http://ollama:11434 --model gemma4:12b --num-threads 8 --num-ctx 8192 --input /input/paint.json --quiet
```

Скрипт `sample-resources.py` пишет JSONL до появления заданного stop-файла:

```bash
python3 reports/deployment/2026-10-29/resource-limits/sample-resources.py resources-new.jsonl stop-new
# В другом терминале после завершения проверок:
touch reports/deployment/2026-10-29/resource-limits/stop-new
```

Health процесса Ollama не доказывает успешный inference или отсутствие OOM дочернего
runner. `check-limits.py` дополнительно проверяет cgroup `memory.events`.
