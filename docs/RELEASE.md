# Релиз на стенд: GitHub Actions → GHCR → Ansible

`release.py` раскатывает **уже собранные** образы через роль
`ansible/roles/repaircalc`. Он не собирает код на стенде и не публикует исходники.
Существующий `make release` остаётся локальной проверкой/сборкой frontend, не деплоем.

В workflow нет встроенных Python/Shell-программ: только Actions и вызовы команд.
Логика находится в `scripts/release.py`, `scripts/github_actions.py` и Ansible role.
Корневой `release.py` — совместимая точка входа в `scripts/release.py`.

## Процесс

1. Push в **`master`** запускает `.github/workflows/ci.yml`:
   - frontend: форматирование, TypeScript, unit/contract-тесты, production build,
     desktop/mobile Playwright с фикстурами и API-моками;
   - Java 21: `mvnw verify`, включая интеграционные Testcontainers-тесты;
   - Python: estimator/classifier без модели (опциональные queue-тесты с
     `CLASSIFIER_QUEUE_TESTS=1` не включаются); release-тесты, Ansible syntax-check,
     проверка Compose без рабочих секретов;
   - только после успешных проверок собираются и публикуются четыре образа
     **linux/amd64**: `frontend`, `backend`, `estimator`, `classifier`.
2. Тег образов — `sha-<полный SHA коммита>`, например
   `ghcr.io/<owner>/<repo>/frontend:sha-…` (namespace в нижнем регистре).
3. **Actions → Deploy release → Run workflow**: выберите environment и вставьте
   полный SHA успешного CI. Дождитесь завершения **всего CI**, включая публикацию
   всех четырёх образов. Workflow проверяет успешный CI именно для push этого SHA
   в `master`; произвольный тег/коммит другой ветки не принимается.
4. После Environment approval Ansible подключается по SSH, скачивает образы,
   запускает базовые сервисы, ждёт healthchecks и проверяет `/healthz` и
   `/api/v1/products` через frontend. Только затем обновляется ссылка `current`.

CI сам не деплоит. Повторный CI для того же SHA может пересобрать SHA-теги;
не перезаписывайте их вручную и ограничьте write-доступ к packages. Для строгой
неизменяемости артефактов дополнительно нужна политика registry/digest-promotion.

## Подготовка стенда (один раз)

- Linux **x86_64**, Python **3.8+**, Docker Engine и Docker Compose **v2.20+**.
- SSH-пользователь с отдельным deployment-ключом и `sudo -n` без запроса пароля.
  Деплой использует `become`; доступ к Docker/root равносилен root-доступу,
  выдавайте его только доверенному пользователю.
- SSH должен быть доступен runner. Для закрытой сети используйте self-hosted runner
  и измените `runs-on`; не открывайте SSH всему интернету ради Actions.
- Доступ к GHCR/Docker Hub; свободное место под образы и PostgreSQL.
- Внешний reverse proxy с HTTPS перед `127.0.0.1:8080`. Роль **не** настраивает
  DNS, сертификаты, firewall, Docker или резервное копирование.

Каталог релиза: `/opt/repaircalc/<environment>/releases/<tag>-<timestamp>-<id>`.
Compose-манифесты и `env/.env` имеют права `0600`, каталоги — `0700`.
Compose project — `repaircalc_<environment>`; volumes переживают релизы.
Пароль существующей БД сохраняйте между релизами: смена `DB_PASSWORD` в файле
не меняет пароль уже созданного PostgreSQL.

На одном хосте environments должны иметь разные `DEPLOY_FRONTEND_PORT`.
Имена environment: `[a-z][a-z0-9-]{0,31}` (например `staging`, `production`).

## GitHub: секреты и переменные

Создайте environments в **Settings → Environments**. Для production включите
required reviewers, запрет self-review и deployment branch rule только для
`master`. Запускайте workflow с `master`.

Настройки ниже создавайте **внутри выбранного Environment**, не в Repository
variables/secrets. Deploy job привязан к `inputs.environment`; обычные параметры
передаются через `env` job, секреты — через `env` шага деплоя. Все скрипты читают
только переменные окружения процесса, не GitHub expressions.

В синтаксисе GitHub Environment variables всё равно доступны через `${{ vars.NAME }}`,
а Environment secrets — через `${{ secrets.NAME }}`: отдельного контекста
`environment.variables` не существует. Нужную область выбирает `job.environment`,
не название контекста. Если настройка отсутствует в Environment, GitHub может
унаследовать одноимённую repository/organization-настройку; не дублируйте параметры
стендов в этих областях, если такой fallback нежелателен.

### Environment Secrets

| Секрет | Значение |
| --- | --- |
| `DEPLOY_SSH_PRIVATE_KEY` | Полный приватный OpenSSH-ключ без passphrase; публичная часть в `authorized_keys` стенда |
| `DB_PASSWORD` | Пароль PostgreSQL; обязателен, не меняйте для существующего volume без отдельной ротации пароля БД |
| `ADMIN_PASSWORD_HASH` | PBKDF2-HMAC-SHA256-хеш пароля администратора; можно оставить пустым для отключения admin API |

SSH использует `StrictHostKeyChecking=accept-new` и стандартный `~/.ssh/known_hosts`:
новый ключ принимается автоматически, изменение известного ключа отклоняется.
На новом GitHub-hosted runner нет истории ключей: это доверие при первом подключении,
**без независимой проверки fingerprint**. Оно не защищает от подмены первого SSH-соединения.

GHCR использует **только встроенный `GITHUB_TOKEN`** — создавать/переносить его не нужно.
CI имеет `packages:write`; деплой — `packages:read` и `actions:read`.
Для private GHCR packages дайте репозиторию Actions read-доступ в настройках
package; отдельный registry token в Environment не используется.

### Environment Variables (не Secrets)

| Переменная | Значение |
| --- | --- |
| `DEPLOY_HOST` | IP/DNS стенда, без схемы и порта |
| `DEPLOY_USER` | Linux SSH-пользователь |
| `DEPLOY_PORT` | SSH-порт, по умолчанию `22` |
| `DEPLOY_FRONTEND_PORT` | Loopback-порт frontend, по умолчанию `8080`, допустимо `1024..65535` |
| `ADMIN_USERNAME` | Логин администратора; по умолчанию пусто (admin API отключён) |
| `ADMIN_COOKIE_SECURE` | `true` по умолчанию; `false` только для закрытого HTTP-стенда |
| `ADMIN_SESSION_SECONDS` | Время сессии, по умолчанию `1800` |
| `OLLAMA_URL` | Адрес подготовленной Ollama, например `http://ollama.internal:11434`; default `http://ollama:11434` |
| `OLLAMA_MODEL` | Имя модели, по умолчанию `gemma4:12b` |
| `OLLAMA_NUM_THREADS` | Потоки inference, по умолчанию `8` |
| `OLLAMA_IMAGE_TAG` | Тег для отдельно включаемого профиля `llm`, по умолчанию `0.40.13` |
| `OLLAMA_CPU_LIMIT` | CPU-лимит отдельно включаемого профиля `llm`, по умолчанию `8.0` |

### Передача настроек приложения

Каждая настройка задаётся отдельно в выбранном GitHub Environment, под своим именем
из таблиц выше. `release.py` сам создаёт временный `stand.env` только из разрешённых
runtime-параметров, Ansible копирует его на стенд как `env/.env`. Временный файл
имеет права `0600` и удаляется после выполнения, включая ошибку деплоя.
Не нужно загружать общий dotenv-файл в GitHub.

Значения Secrets/Variables вводите **как есть, без внешних dotenv-кавычек**.
Скрипт сам экранирует `$`, кавычки и обратные слеши, сохраняя пароли и хеши буквально.
Переводы строк и NUL в настройках приложения запрещены; пустой `DB_PASSWORD`
останавливает деплой до SSH-подключения.

Хеш получите через `python3 backend/scripts/admin-password-hash.py`, см.
[настройку администратора](admin-catalog-implementation.md). Это PBKDF2-HMAC-SHA256,
не открытый пароль. В GitHub Secret переносите только сам хеш из вывода скрипта,
без `ADMIN_PASSWORD_HASH=` и внешних одинарных кавычек.
Для входа администратора нужны обе настройки: `ADMIN_USERNAME` и `ADMIN_PASSWORD_HASH`.

`IMAGE_PREFIX`/`IMAGE_TAG` берутся из репозитория и выбранного SHA, `FRONTEND_PORT`
соответствует `DEPLOY_FRONTEND_PORT`. `VITE_DATA_MODE=api`, `VITE_API_BASE_URL=/api/v1`,
loopback bind и внутренние upstream-адреса зафиксированы production-стеком;
локальные Vite-настройки из `env/.env.example` в GitHub переносить не нужно.
GitHub/SSH-токены в runtime dotenv не попадают.

Релиз не включает профили `llm`/`batch`, не скачивает модель и не запускает
classifier-worker. Для ИИ нужна заранее подготовленная внешняя Ollama.
Без неё Java-расчёт работает, пояснения ИИ недоступны. Существующий bootstrap
импортирует учебный каталог/правила; деплой не превращает их в реальные данные
и не реализует отсутствующие API (см. [deploy.md](deploy.md)).

## Ручной запуск из Linux / macOS / WSL

Controller: Python **3.11+** (Actions использует 3.12). Windows Python Ansible не
поддерживает: запускайте из WSL, используя Linux Python/SSH.

```bash
python3 -m venv .venv-deploy
. .venv-deploy/bin/activate
python -m pip install -r ansible/requirements.txt

export DEPLOY_HOST=stand.example.org
export DEPLOY_USER=deploy
export DEPLOY_SSH_KEY_FILE="$HOME/.ssh/repaircalc_deploy"
chmod 600 "$DEPLOY_SSH_KEY_FILE"
export DB_PASSWORD='replace-with-the-existing-stand-password'
export OLLAMA_URL=http://ollama.internal:11434
# Остальные runtime-параметры — отдельные env из таблиц выше.
# Для private images используйте уже авторизованный GitHub CLI:
export REGISTRY_USERNAME="$(gh api user --jq .login)"
export REGISTRY_PASSWORD="$(gh auth token)"
# Для public images можно не задавать оба REGISTRY_* значения.

python release.py --environment staging \
  --image-prefix ghcr.io/owner/repository \
  --image-tag sha-0123456789abcdef0123456789abcdef01234567 --dry-run

# Та же команда без --dry-run выполнит раскатку.
```

`--dry-run` проверяет параметры, настройки приложения и наличие SSH-ключа,
не соединяется со стендом и не создаёт dotenv-файл.
Локальный скрипт не проверяет статус CI: за выбор проверенного SHA отвечает оператор.
`--no-become` — только если пользователь заранее владеет каталогом и имеет доступ
к Docker. Ошибки Ansible возвращаются ненулевым кодом.

## Сбои и откат

- Это **не zero-downtime**: контейнеры пересоздаются в том же Compose project.
- Перед миграциями сделайте проверенную резервную копию БД. Flyway/init-data могут
  изменить БД ещё до итоговой проверки работоспособности.
- При ошибке `current` остаётся на последнем успешном релизе, но контейнеры уже могут
  быть частично обновлены. Это **не автоматический откат**.
- Для отката выберите предыдущий успешный SHA в workflow, только если он совместим
  с текущей схемой/данными. Иначе нужен отдельный план восстановления БД.
  Volumes не удаляются, `down -v` не выполняется.
- Старые release-каталоги/образы не удаляются автоматически; env-файлы тоже содержат
  секреты. Настройте ограниченную политику хранения и резервного копирования.
- Workflow concurrency и серверный `.deploy-lock` сериализуют раскатки.
  После аварийного обрыва проверьте отсутствие активного деплоя, прежде чем удалить
  **только** пустой `/opt/repaircalc/<env>/.deploy-lock` через `rmdir`.
- Registry credentials находятся во временном `DOCKER_CONFIG`, удаляемом в `always`.
  После жёсткого выключения проверьте оставшиеся `repaircalc-docker-*` в temp.
- Ansible-вывод скрыт через `no_log`, чтобы не раскрывать секреты в GitHub logs.
  Для диагностики используйте на стенде `docker compose` с обоими манифестами
  из `current/docker`, `--env-file ../env/.env`, `-p repaircalc_staging` и
  `IMAGE_PREFIX`/`IMAGE_TAG` нужного релиза. Логи ограничивайте
  `logs --no-color --tail 80 <service>`; проверяйте их перед публикацией.

## Проверки automation без стенда

```bash
python -m unittest discover -s tests/release
ansible-playbook -i localhost, ansible/deploy.yml --syntax-check
```

Команды, вызываемые workflow (входы берутся из окружения):

```bash
python -m scripts.github_actions check-release
python -m scripts.github_actions deploy
python -m scripts.github_actions image-prefix
python -m scripts.github_actions validate-compose
```

Первая проверяет выбранный SHA через GitHub API (`RELEASE_SHA`, `REPOSITORY`,
`GH_TOKEN`). `deploy` готовит временный SSH-ключ и вызывает релиз; `image-prefix`
записывает namespace в `GITHUB_OUTPUT`; `validate-compose` проверяет манифесты
с синтетическим паролем, без настроек стенда.

Рабочие `.env`, SSH-ключи и секреты в Git не добавляются.
