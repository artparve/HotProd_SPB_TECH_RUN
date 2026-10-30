# Запуск Docker

Все команды выполняются из корня. Используйте Docker Engine и Compose v2. GNU Make и Node нужны для локальных convenience-команд; непосредственно Compose работает без них.

```sh
make init-config
# Заполните Mongo-секреты в env/secret.env.
# Для локального HTTP: ADMIN_COOKIE_SECURE=false в env/.env.
make config
make up
```

Открыть `http://127.0.0.1:8080`. Только frontend опубликован наружу. Пример override добавляет диагностические backend/Mongo/LLM-порты на loopback; при размещении на сервере он не используется. `make down` сохраняет volumes. Старый PostgreSQL volume не удаляйте: автоматической миграции нет.

Mongo стартует с ключом, root-пользователем и отдельным прикладным пользователем. `mongo-init` формирует одиночный replica set, `init-data` создаёт индексы, импортирует каталог, правила и подборку. Повторный seed сохраняет активную admin-публикацию. Сначала собирается готовая пара данных, затем Java становится ready. Liveness не требует модели. Frontend доступен даже при неработающем backend.

## Python и Ollama

Базовый стек работает без модели. Для LLM задайте токен от 32 символов в `secret.env`:

```sh
docker compose --env-file env/.env --env-file env/secret.env -f docker/docker-compose.yml --profile llm up -d llm ollama
```

Модель заранее установите отдельно: `docker compose ... exec ollama ollama pull <имя-модели>`. Команда pull не вызывается автоматически. Выберите совместимую с vision/tools модель в `OLLAMA_MODEL`; ресурсы настраиваются env. Для внешней Ollama задайте `OLLAMA_URL` и запустите только сервис `llm`, без `ollama`.

Все публичные API идут через Java. Python доступен только в служебной сети и требует Bearer-токен. Одна очередь допуска ограничивает все виды inference. Планы требуют ручного подтверждения; у интерьерного изображения меняются только поддерживаемые работы и selections, не размеры.

Подборка заполняется по инструкции `data/inspirations/README.md`. Production manifest по умолчанию пустой.

## Диагностика и проверки

`make ps`; логи ограничивайте: `docker compose ... logs --no-color --tail 80 backend`. Не публикуйте `compose config` с подставленными секретами; для проверки используйте `config --quiet`.

```sh
python scripts/verify-stack.py --playwright
```

Скрипт собирает три образа и запускает отдельный проект со случайным именем, синтетическими credentials и картинкой. Java, MongoDB replica set, Python и nginx настоящие; fake Ollama проверяет транспорт без модели. Проверяются калькуляторы, галерея, admin/ETag/очередь/review/publication, seed, backup/restore, отказы и восстановление. Удаляются только volumes этого тестового проекта. Для ограниченной сети: `--maven-repository https://maven-central.storage-download.googleapis.com/maven2`.

При `--playwright` браузер проверяет также состояние UI после отдельной остановки Ollama, Python, Mongo и Java. `--base-only --no-build` проверяет уже собранные образы без Python/Ollama/токена, с пустым production manifest.

## Backup и restore

```sh
python scripts/mongo-backup.py backup --file backups/repaircalc.archive.gz
python scripts/mongo-backup.py restore --file backups/repaircalc.archive.gz --target-database repaircalc_restore_check
```

Backup ненадолго останавливает Java, единственного владельца записи, и запускает его в finally. Это обеспечивает согласованную копию связанных коллекций; admin-сессии после рестарта надо открыть заново. Рядом создаётся JSON с SHA-256 и счётчиками коллекций. Пароль остаётся внутри Mongo-контейнера во временном config-файле с mode 600, не передаётся в argv.

Restore разрешён только в новую пустую БД, проверяет хеш архива и счётчики, не заменяет исходную БД. Для перехода на восстановленную БД задайте её в конфигурации приложения и создайте scoped Mongo-пользователя. Сохраняйте также env-секреты в защищённом хранилище и подготовленные изображения/manifest либо соответствующий неизменяемый release image. Архив БД содержит метаданные изображений, не сами PNG/JPEG.

Для другого Compose project или release-файлов задайте `--project-name`, повторяемые `--env-file` и `--compose-file`. Используемые механизмы описаны в официальной документации [mongodump](https://www.mongodb.com/docs/database-tools/mongodump/) и [mongorestore](https://www.mongodb.com/docs/database-tools/mongorestore/).
