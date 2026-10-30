# RepairCalc: полный стек Docker Compose

Деплой готовых образов через GitHub Actions и Ansible: [RELEASE.md](RELEASE.md).

Команды выполняются из корня репозитория. Нужны Docker Engine с Linux containers,
Compose v2.20+ и GNU Make. Для `make init-config` нужен Node.js; на Docker-only хосте
можно вручную скопировать `env/.env.example` в `env/.env`, `env/secret.env.example`
в `env/secret.env`, а
`docker/docker-compose.override.yml.example` в `docker/docker-compose.override.yml`.
Java, Maven и Python на хосте не требуются. Первая сборка требует сети.

## Быстрый запуск без LLM

```bash
make init-config
# В env/secret.env задайте DB_PASSWORD перед первым запуском.
make config
make up
make ps
```

Откройте http://127.0.0.1:8080. По умолчанию Compose собирает интерфейс в режиме `api`,
независимо от `VITE_DATA_MODE` для локального Vite. В карточке товара снимите
«Добавить пояснение ИИ»: расчёт выполняется без модели. Общая смета и рекомендации
пока требуют ещё не реализованных API; проверяйте витрину и калькулятор товара.

`make up` собирает образы и ожидает готовности постоянных сервисов.
`init-data` штатно завершается с кодом 0: последовательно импортирует 12 учебных
товаров и совместимые правила. Цены и нормы учебные. Повтор тех же импортов — no-op;
ошибка импорта останавливает запуск backend. Этот bootstrap предназначен для текущего
демокаталога. При переходе к рабочим данным замените bootstrap на явную процедуру
публикации согласованных версий; не редактируйте учебные JSON без изменения версий.

## Сервисы и данные

| Сервис | Назначение | Доступ |
| --- | --- | --- |
| frontend | Vue SPA, nginx и проксирование API | host 127.0.0.1:8080 |
| backend | Java: каталог, правила, точные расчёты | backend:8000 |
| postgres | PostgreSQL 17.11 | postgres:5432 |
| init-data | Однократный импорт каталога, затем правил | Завершается до старта backend |
| estimator | Python: Java tool и объяснения LLM | estimator:8010 |
| ollama | Модель, профиль `llm` | ollama:11434 |
| classifier | Пакетный CLI, профиль `batch` | HTTP-сервера нет |

nginx направляет `/api/v1/materials/` в estimator, остальные `/api/` в backend.
Estimator обращается в Java за расчётом. PostgreSQL доступен backend и задаче импорта;
базовый/release Compose не публикует их порты. Local override публикует их только
на loopback: PostgreSQL 55432, Java 8000, Python 8010, Ollama 11434 (профиль `llm`).
Сервисы используют общую сеть Compose; это не отдельные сетевые зоны доступа.

Постоянные volumes: `repaircalc_pgdata` и `repaircalc_ollama-data`.
Они независимы от ранее вручную созданного `repaircalc-pgdata`: старые данные
автоматически не переносятся. `make down` сохраняет volumes. `down -v` удаляет их.
Пароль уже созданной БД не меняется при изменении DB_PASSWORD в env-файле.
Не выводите полный `compose config` в публичные логи: в нём раскрывается пароль.

Внутри контейнеров адреса зависимостей заданы именами сервисов. Переменные
`API_UPSTREAM`, `ESTIMATION_UPSTREAM` относятся к Vite на хосте;
Устаревшие `DOCKER_API_UPSTREAM`/`DOCKER_ESTIMATION_UPSTREAM` удалены из общего шаблона.
Публикация порта frontend остаётся в локальном override. `FRONTEND_HOST` и
`FRONTEND_PORT` задаются в env/.env. Файлы локальных настроек Make не перезаписывает.

Compose получает два файла: `--env-file env/.env --env-file env/secret.env`.
Пароли и admin-хеш находятся только во втором. Старые значения перенесите вручную,
не меняя пароль существующей БД. Общая схема и host-запуск: [CONFIG.md](CONFIG.md).

## Ollama и модель

Ollama включается явно. Веса не скачиваются при обычном старте стека.

```bash
docker compose --env-file env/.env --env-file env/secret.env -f docker/docker-compose.yml --profile llm up -d --wait ollama
docker compose --env-file env/.env --env-file env/secret.env -f docker/docker-compose.yml exec ollama ollama pull gemma4:12b
```

После этого можно включить пояснение ИИ в карточке товара. Тег в команде pull
должен совпадать с `OLLAMA_MODEL` в env/.env. Образ Ollama закреплён на `0.40.13`;
`OLLAMA_IMAGE_TAG` позволяет выбрать другую версию. CPU inference может занимать
минуты. Estimator обрабатывает один запрос к модели за раз; его `/health`
подтверждает жизнь процесса, но не наличие модели и не готовность Java.
Готовность Java проверяется отдельно через readiness, включая БД и опубликованные данные.

Для NVIDIA GPU установите NVIDIA Container Toolkit на хосте и добавьте override:

```bash
docker compose --env-file env/.env --env-file env/secret.env -f docker/docker-compose.yml -f docker/docker-compose.gpu.yml --profile llm up -d --wait ollama
```

Поддержку GPU и достаточный объём VRAM нужно проверить на целевом сервере.
Для уже существующей внешней Ollama задайте `OLLAMA_URL` доступным из контейнера
адресом и пересоздайте estimator через `make up`. Встроенную Ollama тогда можно не запускать.

## Классификатор

После старта Ollama и загрузки модели:

```bash
docker compose --env-file env/.env --env-file env/secret.env -f docker/docker-compose.yml --profile batch build classifier
docker compose --env-file env/.env --env-file env/secret.env -f docker/docker-compose.yml --profile batch run --rm classifier
# Реальная классификация одного примера; результат — stdout:
docker compose --env-file env/.env --env-file env/secret.env -f docker/docker-compose.yml --profile batch run --rm classifier --host http://ollama:11434 --model gemma4:12b --input /input/paint.json
```

Без аргументов контейнер проверяет сервер и наличие модели (`--status`), без inference.
При передаче аргументов заменяется вся команда: явно указывайте host и model.
В контейнер смонтированы только примеры для чтения. Для полного каталога добавьте
read-only bind mount в локальный override и передайте его путь через `--json-catalog`
или `--xml-catalog`; крупные исходники не входят в образы. Не запускайте пакетную
классификацию одновременно с пользовательскими объяснениями на одной Ollama.

## Проверка и управление

```bash
curl --fail http://127.0.0.1:8080/healthz
curl --fail http://127.0.0.1:8080/api/v1/products
curl --fail -H 'Content-Type: application/json' --data '{"area":20,"perimeter":18,"height":2.7,"reserve":10,"layers":2,"quantity":6}' http://127.0.0.1:8080/api/v1/product/ac1f222a-f5e9-3b5d-a91e-c943b053e92e/calc
make logs
make down
```

Ожидается 12 товаров; для этой плитки — 16 упаковок и 34560.00 RUB.
`make image` собирает образы приложений без запуска; Dockerfile Java пропускает тесты
при упаковке, поэтому изменение Java требует отдельного `backend/mvnw verify`.
`make down` выключает базовые сервисы. Если запускали Ollama, для остановки всех
профилей используйте:

```bash
docker compose --env-file env/.env --env-file env/secret.env -f docker/docker-compose.yml -f docker/docker-compose.override.yml --profile llm --profile batch down
```

`COMPOSE_CLI` в Makefile можно переопределить путём к standalone Compose v2.
Состояние `healthy` не доказывает корректность всех бизнес-сценариев;
реальный LLM-вызов проверяется отдельно. Стек предназначен для локальной разработки
и демо; интернет-публикация требует HTTPS, аутентификации и настройки доступа.

## Нужен ли Kubernetes

Для текущего MVP на одном сервере рекомендован Docker Compose. Kubernetes имеет
смысл при нескольких узлах, требованиях переживать отказ узла, независимом
масштабировании API/пакетных задач и наличии команды, которая обслуживает кластер.
Один узел Kubernetes сохраняет единственную точку отказа и добавляет компоненты
управления. См. [официальные требования production Kubernetes](https://kubernetes.io/docs/setup/production-environment/).

До миграции потребуется отдельно решить хранение и резервное копирование PostgreSQL,
GPU scheduling и доступность модели, согласование конкурентных LLM-запросов,
процедуру публикации каталога и readiness. Добавление реплик estimator само по себе
не увеличит производительность одной Ollama: локальный ограничитель запросов не
согласуется между процессами. Kubernetes-манифесты сейчас не добавляются.

Результаты фактической проверки Compose: [отчёт от 2026-10-29](../reports/deployment/2026-10-29/README.md).

## Ограничения ресурсов

Лимиты применяются самим Docker через `cpus`, `mem_limit` и `memswap_limit`.
Сумма лимитов постоянных сервисов — 10,25 CPU и 13,625 GiB RAM; это верхние
границы, а не предварительное резервирование памяти. Для Ollama по умолчанию
выделено 8 CPU и 12 GiB RAM; Java — 1 CPU / 768 MiB, PostgreSQL — 0,5 CPU /
512 MiB, estimator — 0,5 CPU / 256 MiB, nginx — 0,25 CPU / 128 MiB.
Classifier имеет отдельный лимит 0,5 CPU / 384 MiB; вычисления модели происходят
в Ollama. Swap для каждого сервиса запрещён через равные memory и memory+swap
лимиты; это не отключает swap хоста или виртуальной машины Docker Desktop.

В `env/.env` можно задать `OLLAMA_CPU_LIMIT=8.0` и `OLLAMA_NUM_THREADS=8`.
Первое — жёсткая квота CPU контейнера; второе передаётся estimator в Ollama API
как `options.num_thread`. Одна лишь настройка потоков не заменяет cgroup-квоту.
Ollama допускает один одновременный запрос и одну загруженную модель, выгружает
её через 60 секунд простоя. Контекст estimator остаётся 8192 токена.
Для ручной классификации передавайте `--num-threads 8 --num-ctx 8192`.
Классификацию каталога и интерактивные объяснения запускайте последовательно.

Текущая локальная проверка выполнена в Docker Desktop с 14 vCPU и 16 GiB RAM.
На этой машине локальный игнорируемый override использует CPU-образ Ollama 0.40.2,
собранный из установленного Snap и каталога `docker/local-ollama-runtime/`.
Модель `gemma4:12b` перенесена в named volume из существующей локальной установки.
Эти бинарные файлы не входят в Git; базовый Compose по-прежнему использует
официальный `ollama/ollama:0.40.13`. При переносе на другую машину используйте
официальный образ и загрузите веса отдельно.

[Справочник лимитов Docker Compose](https://docs.docker.com/reference/compose-file/services/).
[Повторный прогон с лимитами и замеры нагрузки](../reports/deployment/2026-10-29/resource-limits/README.md).

Для текущего backend `llama-server` дополнительно отключены межзапросный RAM-кэш
(`LLAMA_ARG_CACHE_RAM=0`) и checkpoints (`LLAMA_ARG_CTX_CHECKPOINTS=0`). Сам контекст
модели 8192 не уменьшен. Это предотвращает накопление копий KV-состояния при смене
задач; совместимость проверяется по строкам runner `prompt cache is disabled` и
`context checkpoints disabled`. При обновлении Ollama эти параметры нужно проверить
снова: они относятся к bundled llama-server, а не к универсальному Ollama API.
Описание upstream-проблемы и обходного решения:
[Ollama issue 18264](https://github.com/ollama/ollama/issues/18264).
