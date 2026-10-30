# Релиз: GitHub Actions → GHCR → Ansible

CI запускается для PR и push в `backend`/`main`. Он проверяет frontend, Java/Mongo Testcontainers, Python, конфигурацию и сквозной Docker/API/Playwright-прогон. На PR публикации нет. После успешных проверок доверенного push публикуются три образа `frontend`, `backend`, `llm` с тегом `sha-<полный SHA>`.

Workflow Deploy release запускается вручную из `main`, требует успешный push-CI для выбранного SHA именно в `main` и использует выбранный GitHub Environment. Он не пересобирает код на стенде. Код контроллера находится в `scripts/release.py`, `scripts/github_actions.py`; роль — `docker/ansible`.

## Настройка environment

Задайте DEPLOY_HOST, DEPLOY_USER, DEPLOY_PORT, DEPLOY_FRONTEND_HOST/PORT, SSH private key в DEPLOY_SSH_PRIVATE_KEY и Mongo secrets MONGO_ROOT_PASSWORD/MONGO_PASSWORD/MONGO_KEYFILE_SECRET. Для admin нужны ADMIN_USERNAME/ADMIN_PASSWORD_HASH. Обычные runtime-настройки экспортируются из GitHub vars; значения по умолчанию совпадают с env/.env.example.

DEPLOY_LLM_ENABLED=false оставляет только базовый стек. true добавляет Python; DEPLOY_LOCAL_OLLAMA=true дополнительно запускает контейнер Ollama. При false задайте внешний OLLAMA_URL. В обоих случаях LLM требует INTERNAL_SERVICE_TOKEN. Модели не скачиваются автоматически.

Роль создаёт отдельный Compose project на environment, копирует только manifests/init scripts и env, подтягивает готовые образы, повторяет bootstrap и ждёт healthchecks. Frontend проверяется отдельно от Java; деградировавший пустой каталог не считается успешным релизом. Указатель current меняется после проверок. Registry credentials и deploy lock удаляются в always, env-файлы имеют mode 600. Обновление приложения сохраняет Mongo volume и не трогает старый PostgreSQL volume.

## Локальный контроллер

Нужны Linux/macOS/WSL, Python и зависимости docker/ansible/requirements.txt. Пример проверки входов без SSH:

```sh
python scripts/release.py --environment staging --image-tag sha-<40-hex> --image-prefix ghcr.io/<owner>/<repo> --dry-run
```

Реальное размещение запускается той же командой без --dry-run при настроенных credentials. Это отдельная операторская операция; сквозные тесты деплой на внешний сервер не выполняют.

Откат: заново развернуть проверенный предыдущий SHA приложения. Новые Mongo-данные сохраняются; восстановление БД выполняется отдельно из проверенного архива по docs/deploy.md. Одноузловой replica set обеспечивает транзакции, но не высокую доступность.
