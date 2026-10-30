# Общая конфигурация и секреты

Шаблоны хранятся только в `env/`; отдельный `backend/.env.example` объединён с ними.

| Файл | Содержимое | В Git |
| --- | --- | --- |
| `env/.env.example` | Общие настройки Java, Vite, Python и Compose, без паролей | Да |
| `env/secret.env.example` | Имена секретов с пустыми значениями | Да |
| `env/.env` | Локальные адреса, порты, имя/пользователь БД, модель и лимиты | Нет |
| `env/secret.env` | `DB_PASSWORD`, `ADMIN_PASSWORD_HASH` | Нет |

`make init-config` создаёт отсутствующие файлы, не заменяя существующие. Заполните
`DB_PASSWORD` в `secret.env`; для уже созданной БД используйте её текущий пароль.
Перенесите туда `ADMIN_PASSWORD_HASH`, если он настроен. Локальные файлы агент не
читает и не переписывает: **из старого `.env` эти два ключа нужно удалить вручную**
после переноса значений. Старые локальные Docker overrides тоже не перезаписываются;
при необходимости добавьте loopback-порты из нового `.override.yml.example`.

## Что загружает каждый способ запуска

- **Vite** читает только `env/.env` и свои mode-файлы. `secret.env` ему не передаётся.
  `VITE_*` публичны и попадают в браузерный bundle — никаких секретов в этих ключах.
- **`make backend`, `make backend-import-catalog`, `make backend-import-rules`,
  `make estimator`** используют Node.js 22.12+ и `scripts/local.mjs`. Общие defaults
  берутся из `.env.example`, затем `.env`, `secret.env`, затем env процесса.
  Пароли передаются дочернему процессу через окружение, не аргументы командной строки.
  Estimator обращается к Java по HTTP, поэтому DB/admin-секреты из его child env удаляются.
  Java JAR предварительно соберите Maven Wrapper; Python-зависимости установите отдельно.
- **`make up`, `make dependencies`, `make config`, `make image`** передают Compose
  `--env-file env/.env --env-file env/secret.env`: второй файл имеет приоритет над
  первым, env процесса — над обоими. `make dependencies` запускает только PostgreSQL.
- **GitHub Actions** получает отдельные Variables/Secrets выбранного Environment.
  Релиз генерирует два закрытых файла и Ansible устанавливает на стенд
  `env/.env` и `env/secret.env`. Загружать целый dotenv в GitHub не требуется.

Spring Boot и Python CLI сами эти файлы **не** загружают. При прямом запуске без
`make` экспортируйте переменные сами. Локальный Node-loader не выполняет shell-код
и не раскрывает `${NAME}`: пишите конечные значения, не выражения подстановки.
Для PBKDF2-хеша используйте одинарные кавычки в `secret.env`, как выдаёт
`python3 backend/scripts/admin-password-hash.py`. В GitHub Secret кавычки не нужны.

## Настройки, добавленные в общий шаблон

| Группа | Ключи | Default |
| --- | --- | --- |
| Локальная Java | `SPRING_PROFILES_ACTIVE`, `SERVER_ADDRESS`, `SERVER_PORT` | `local`, `127.0.0.1`, `8000` |
| База данных | `DB_NAME`, `DB_USER`, `DB_URL` | `repaircalc`, `repaircalc`, JDBC localhost:55432 |
| PostgreSQL | `POSTGRES_IMAGE_TAG`, `POSTGRES_HOST_PORT` | `17.11`, `55432` |
| Vite | `DEV_HOST`, `DEV_PORT` | `127.0.0.1`, `5173` |
| Локальный estimator | `ESTIMATOR_ADDRESS`, `ESTIMATOR_PORT` | `127.0.0.1`, `8010` |
| Локальная Ollama | `LOCAL_OLLAMA_URL`, `OLLAMA_PORT` | `http://127.0.0.1:11434`, `11434` |
| Память Ollama | `OLLAMA_MEMORY_LIMIT` | `12g`, одинаково для memory и memory+swap |

Также сохранены API-префикс, upstream-адреса Vite, frontend bind/port, image tag,
admin-настройки, модель и CPU/threads Ollama. Устаревшие `DOCKER_API_UPSTREAM` и
`DOCKER_ESTIMATION_UPSTREAM` убраны из шаблона: полный стек их не использовал.

При изменении локальных портов обновляйте связанные значения: `DB_URL` должен
соответствовать `POSTGRES_HOST_PORT` и `DB_NAME`, `API_UPSTREAM` — `SERVER_PORT`,
`ESTIMATION_UPSTREAM` — `ESTIMATOR_PORT`, `LOCAL_OLLAMA_URL` — `OLLAMA_PORT`.
В контейнерах DB_URL всегда указывает на `postgres:5432/<DB_NAME>`, сервисные порты
и DNS-имена остаются стабильны. Это предотвращает передачу localhost-адресов внутрь Docker.

Имя/пользователь БД и версия PostgreSQL не являются процедурой миграции существующего
volume. Их изменение требует отдельного плана миграции/backup; смена пароля в файле
не меняет пароль PostgreSQL. Модель и внешнюю Ollama по-прежнему готовят отдельно.

## Локальные зависимости

Шаблон local override публикует только на `127.0.0.1`: PostgreSQL `55432`, Java
`8000`, estimator `8010`, Ollama `11434` (только при включённом профиле `llm`).
Это позволяет запускать Vite отдельно от backend-контейнеров или Java на хосте
с PostgreSQL от `make dependencies`. Не запускайте контейнерную и host-версию
одного сервиса одновременно на одном порту. Release override этих портов не публикует.

Не следует выносить в env формулы, округление, совместимость товаров, миграции и
секреты в build args. Остальные CPU/memory лимиты можно параметризовать при наличии
разных ресурсных профилей стендов; сейчас они оставлены явными в Compose.
