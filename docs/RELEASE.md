# Релиз: GitHub Actions → GHCR → Ansible

CI запускается для PR и push в `backend`/`main`. Он проверяет frontend, Java/Mongo Testcontainers, Python, конфигурацию и сквозной Docker/API/Playwright-прогон. На PR публикации нет. После успешных проверок доверенного push публикуются три образа `frontend`, `backend`, `llm` с тегом `sha-<полный SHA>`.

Workflow `CD` (`.github/workflows/cd.yml`) запускается вручную из `main`, требует успешный push-CI для выбранного SHA именно в `main` и использует выбранный GitHub Environment. SHA необязателен: по умолчанию выбирается commit запуска workflow. Он определяет immutable images; инструменты деплоя берутся из commit запуска, поэтому выбор старого образа не отменяет исправления безопасности деплоя. Код контроллера находится в `scripts/release.py`, `scripts/github_actions.py`; роль — `docker/ansible`.

## Настройка environment

Задайте DEPLOY_HOST, DEPLOY_USER, DEPLOY_PORT, DEPLOY_FRONTEND_HOST/PORT, SSH private key в DEPLOY_SSH_PRIVATE_KEY и Mongo secrets MONGO_ROOT_PASSWORD/MONGO_PASSWORD/MONGO_KEYFILE_SECRET. Для admin нужны ADMIN_USERNAME/ADMIN_PASSWORD_HASH. Обычные runtime-настройки экспортируются из GitHub vars; значения по умолчанию совпадают с env/.env.example.

DEPLOY_LLM_ENABLED=false оставляет только базовый стек. true добавляет Python; DEPLOY_LOCAL_OLLAMA=true дополнительно запускает контейнер Ollama. При false задайте внешний OLLAMA_URL. В обоих случаях LLM требует INTERNAL_SERVICE_TOKEN. Модели не скачиваются автоматически.

Роль создаёт отдельный Compose project на environment, копирует только manifests/init scripts и env, подтягивает готовые образы, повторяет bootstrap и ждёт healthchecks. Frontend проверяется отдельно от Java; деградировавший пустой каталог не считается успешным релизом. Указатель current меняется после проверок. Registry credentials и deploy lock удаляются в always, env-файлы имеют mode 600. Обновление приложения сохраняет Mongo volume и не трогает старый PostgreSQL volume.

## Проверка существующего стенда и сохранение данных

`scripts/deploy_target.py` перед изменением сервисов проверяет владельца frontend-порта, Compose project, Mongo volume, имя базы/пользователя, major-версию Mongo, replica set и активную пару каталога/правил. Прикладные и root-реквизиты проверяются через Docker bridge, не через localhost. Пароли передаются mongosh через stdin и не попадают в argv или открытый отчёт.

В `prod` здоровый Mongo-стек `repaircalc` на целевом порту может быть принят с сохранением его `repaircalc_mongo-data`. Выбор закрепляется в `compose-project.json`; смена порта или временно остановленный frontend не должны переключить данные на новый volume. PostgreSQL-стек автоматически не принимается и не преобразуется в Mongo: нужен новый Mongo project на свободном порту и отдельное переключение ingress оператором. Старые PostgreSQL контейнеры и volumes не удаляются.

Для существующей публикации `docker-compose.preserve-data.yml` пропускает импорт учебных каталога/правил, но всегда импортирует manifest изображений выбранного релиза; Java продолжает создавать индексы. Поэтому обновление с пустой или старой галереей получает новую подборку без сброса активной публикации каталога. При собственном INSPIRATIONS_MANIFEST импортируется именно настроенный manifest. Перед обновлением выполняется согласованный Mongo backup с SHA-256 и счётчиками коллекций. Он кратко останавливает прежний Java writer и запускает его в finally. Архив `database-before.archive.gz` и sidecar остаются в закрытом каталоге release. Повторный запуск использует новый release ID и не перезаписывает прошлый backup.

При ошибке `scripts/deploy_diagnostics.py` сохраняет ограниченные логи и детали на стенде в `deployment-diagnostics.json` с mode 600; в Actions выводятся только типы сбоев и состояния контейнеров. Сырые логи автоматически не публикуются. Автоматического отката БД нет; указатель `current` меняется только после успешных health/API проверок.

Перед login/backup/рестартом preflight также проверяет выбранный Mongo image на фактическом Docker host. Ядра до 6.19 находятся вне известной rseq-регрессии; для более новых/неизвестных ядер выполняются изолированный replica set, транзакция и минимум 91 секунда CRUD. Несовместимое ядро даёт `mongodb_kernel_incompatible` и блокирует релиз. Изображение проверяется без рабочего volume и публичных портов. Детали: [CI_RUNTIME.md](CI_RUNTIME.md).

### Ручной перенос PostgreSQL в MongoDB

`scripts/migrate_postgres.py` выполняет явный экспорт согласованного read-only snapshot и импорт только в пустую MongoDB. Сохраняются исходные строки, идентификаторы, история админки и точные десятичные значения. Неизвестные таблицы, незавершённые задания и нарушенные ссылки блокируют перенос. Для старых правил добавляются обязательные kits и priority; materialFamily переносится из эталонного каталога только при точном совпадении идентификатора и исходных товарных полей. Передавайте проверенные `--reference-rules` и `--reference-catalog`.

Перенос требует остановки всех исходных writers, полных PostgreSQL backup с проверкой восстановления и сравнения каталога, расчётов и админки через приложение. Затем выполняется CD в `prod` на свободном порту; ingress переключается отдельно после успеха CD. Старые контейнеры удаляются только после проверки сайта, без удаления volumes и backup. Сырые snapshots, дампы и конфигурация с credentials остаются в закрытом каталоге сервера и не коммитятся. Операторские проверки этого переноса находятся в `reports/cd/2026-10-31/`.

## Локальный контроллер

Нужны Linux/macOS/WSL, Python и зависимости docker/ansible/requirements.txt. Пример проверки входов без SSH:

```sh
python scripts/release.py --environment staging --image-tag sha-<40-hex> --image-prefix ghcr.io/<owner>/<repo> --dry-run
```

Реальное размещение запускается той же командой без --dry-run при настроенных credentials. Это отдельная операторская операция; сквозные тесты деплой на внешний сервер не выполняют.

Откат: заново развернуть проверенный предыдущий SHA приложения. Новые Mongo-данные сохраняются; восстановление БД выполняется отдельно из проверенного архива по docs/deploy.md. Одноузловой replica set обеспечивает транзакции, но не высокую доступность.
