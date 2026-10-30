# Релиз на стенд: GitHub Actions → GHCR → Ansible

`release.py` раскатывает **уже собранные** образы через роль
`ansible/roles/repaircalc`. Он не собирает код на стенде и не публикует исходники.
Существующий `make release` остаётся локальной проверкой/сборкой frontend, не деплоем.

В workflow нет встроенных Python/Shell-программ: только Actions и вызовы команд.
Логика находится в `scripts/release.py`, `scripts/github_actions.py` и Ansible role.
Корневой `release.py` — совместимая точка входа в `scripts/release.py`.

## Процесс

1. Push в **`main`** запускает `.github/workflows/ci.yml`:
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
3. **Actions → CD → Run workflow** (`.github/workflows/cd.yml`): выберите environment.
   SHA необязателен: без него берётся последний коммит `main` на момент запуска
   workflow (`github.sha`), а не последний успешный CI. Для отката можно явно указать
   полный SHA другого успешного CI из `main`. Дождитесь завершения **всего CI**,
   включая публикацию всех четырёх образов: иначе CD завершится ошибкой, не начав деплой.
   Workflow проверяет успешный CI именно для push выбранного SHA в `main`;
   произвольный тег/коммит другой ветки не принимается. Один и тот же SHA используется
   для проверки CI и тега образов — последующие push/approvals его не меняют.
   Автоматика/Compose-манифесты берутся из коммита запуска CD (`github.sha`),
   даже при выборе старого SHA образов: исправления безопасного деплоя не откатываются.
   Совместимость старых образов с актуальными манифестами/схемой нужно проверить отдельно.
4. После Environment approval Ansible проверяет владельца порта и текущую БД,
   скачивает образы, делает backup существующей БД, обновляет базовые сервисы,
   ждёт healthchecks и проверяет `/healthz` и `/api/v1/products` через frontend.
   Только затем обновляется ссылка `current`.

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
- HTTPS через внешний reverse proxy. При proxy на том же хосте задайте
  `DEPLOY_FRONTEND_HOST=127.0.0.1`, чтобы закрыть прямой внешний доступ.
  В Actions default — `0.0.0.0`: frontend публикуется на всех IPv4-интерфейсах,
  поэтому ограничьте доступ firewall/security groups. Роль **не** настраивает
   DNS, сертификаты, firewall, Docker или регулярное/off-host резервное копирование.

Каталог релиза: `/opt/repaircalc/<environment>/releases/<tag>-<timestamp>-<id>`.
Compose-манифесты, `env/.env` и `env/secret.env` имеют права `0600`, каталоги — `0700`.
Для нового стенда Compose project — `repaircalc_<environment>`; при первом
переходе `prod` со старого локального стека автоматически сохраняется проект
`repaircalc` и его действующий volume (подробнее ниже). Выбор закрепляется в
`/opt/repaircalc/<environment>/compose-project.json`; volumes переживают релизы.
Пароль существующей БД сохраняйте между релизами: смена `DB_PASSWORD` в файле
не меняет пароль уже созданного PostgreSQL.

На одном хосте environments должны иметь разные `DEPLOY_FRONTEND_PORT`.
Имена environment: `[a-z][a-z0-9-]{0,31}` (например `staging`, `production`).

## GitHub: секреты и переменные

Создайте environments в **Settings → Environments**. Для production включите
required reviewers, запрет self-review и deployment branch rule только для
`main`. Запускайте workflow с `main`.

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
| `DEPLOY_FRONTEND_HOST` | IP привязки frontend без порта; в Actions default `0.0.0.0`, для локального reverse proxy задайте `127.0.0.1` |
| `DEPLOY_FRONTEND_PORT` | Порт frontend, по умолчанию `8080`, допустимо `1024..65535` |
| `DB_NAME` | Имя PostgreSQL database, по умолчанию `repaircalc` |
| `DB_USER` | Пользователь PostgreSQL, по умолчанию `repaircalc` |
| `POSTGRES_IMAGE_TAG` | Версия PostgreSQL, по умолчанию `17.11`; обновление major требует отдельной миграции |
| `ADMIN_USERNAME` | Логин администратора; по умолчанию пусто (admin API отключён) |
| `ADMIN_COOKIE_SECURE` | `true` по умолчанию; `false` только для закрытого HTTP-стенда |
| `ADMIN_SESSION_SECONDS` | Время сессии, по умолчанию `1800` |
| `OLLAMA_URL` | Адрес подготовленной Ollama, например `http://ollama.internal:11434`; default `http://ollama:11434` |
| `OLLAMA_MODEL` | Имя модели, по умолчанию `gemma4:12b` |
| `OLLAMA_NUM_THREADS` | Потоки inference, по умолчанию `8` |
| `OLLAMA_IMAGE_TAG` | Тег для отдельно включаемого профиля `llm`, по умолчанию `0.40.13` |
| `OLLAMA_CPU_LIMIT` | CPU-лимит отдельно включаемого профиля `llm`, по умолчанию `8.0` |
| `OLLAMA_MEMORY_LIMIT` | RAM/memory+swap лимит профиля `llm`, по умолчанию `12g` |

### Передача настроек приложения

Каждая настройка задаётся отдельно в выбранном GitHub Environment, под своим именем
из таблиц выше. `release.py` создаёт два временных файла только из разрешённых
runtime-параметров: `.env` без секретов и `secret.env` с `DB_PASSWORD`/`ADMIN_PASSWORD_HASH`.
Ansible копирует их в `env/` релиза и передаёт Compose два `--env-file` в этом порядке.
Оба файла имеют права `0600` и удаляются на controller после выполнения, включая ошибку деплоя.
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

`IMAGE_PREFIX`/`IMAGE_TAG` берутся из репозитория и выбранного SHA, `FRONTEND_HOST`
и `FRONTEND_PORT` соответствуют `DEPLOY_FRONTEND_HOST` и `DEPLOY_FRONTEND_PORT`.
У ручного `release.py` и Compose без явного адреса сохраняется default `127.0.0.1`;
Actions явно передаёт `0.0.0.0`, если в Environment не задан другой адрес.
`VITE_DATA_MODE=api`, `VITE_API_BASE_URL=/api/v1` и внутренние upstream-адреса зафиксированы production-стеком;
локальные Vite-настройки из `env/.env.example` в GitHub переносить не нужно.
GitHub/SSH-токены в runtime dotenv не попадают.

Релиз не включает профили `llm`/`batch`, не скачивает модель и не запускает
classifier-worker. Для ИИ нужна заранее подготовленная внешняя Ollama.
Без неё Java-расчёт работает, пояснения ИИ недоступны. В новой БД bootstrap
импортирует учебный каталог/правила; в БД с уже импортированным каталогом
используется `docker-compose.preserve-data.yml`: bootstrap ничего не импортирует,
а Flyway выполняется при старте backend. Текущие публикации/admin-данные сохраняются.
Деплой не превращает учебные данные в реальные и не реализует отсутствующие API
(см. [deploy.md](deploy.md)). Импорт новой версии каталога/правил — отдельная операция.

## Автоматический переход со старого стека

При первом CD для Environment **`prod`** скрипт `scripts/deploy_target.py` проверяет
контейнеры до перезапуска сервисов. Если целевой порт уже принадлежит здоровому
frontend проекта `repaircalc`, и его backend/PostgreSQL тоже здоровы, роль обновляет
**этот же проект** готовыми образами. Используется прежний `repaircalc_pgdata`,
а не новая учебная БД `repaircalc_prod_pgdata`. Номер порта не меняется, поэтому
Caddy с upstream `127.0.0.1:8080` перенастраивать не нужно. Для такой конфигурации
оставьте `DEPLOY_FRONTEND_HOST=127.0.0.1`, `DEPLOY_FRONTEND_PORT=8080`.

Перед первым рестартом:

1. Проверяются `DB_USER`/`DB_NAME`, TCP-подключение с `DB_PASSWORD` к bridge-адресу
   PostgreSQL в Docker-сети (не к `127.0.0.1`),
   прежний named volume и совместимость major-версии PostgreSQL.
   В стандартном образе PostgreSQL localhost TCP часто использует `trust`, поэтому
   проверка через localhost не доказывает, что пароль подходит для backend.
2. Если раньше admin был включён, пустые `ADMIN_USERNAME`/`ADMIN_PASSWORD_HASH`
   не принимаются: задайте их в GitHub Environment. Секреты автоматически не копируются.
3. После pull образов создаётся `database-before.dump` в приватном каталоге релиза
   (custom-format `pg_dump`, права `0600`); проверяется чтение оглавления через
   `pg_restore --list`. Ошибка backup останавливает деплой **до** `rm`/`up`.
   Это согласованный снимок БД, но не проверка полного восстановления и не замена
   регулярным off-host backups. Дамп содержит данные приложения; не публикуйте его.
4. Для существующей БД проект/volume закрепляются до рестарта; при неудачном старте
   повторный CD не переключится на другую БД, даже если frontend временно не работает.
   На чистом стенде выбор закрепляется после health/API-проверок, чтобы ошибка
   до создания PostgreSQL не блокировала повторную попытку первой установки.

Это обновление **на месте**, не blue/green: возможен краткий простой. Не удаляйте
`compose-project.json` ради смены проекта — это защита от случайной смены БД.
Существующие Ollama/classifier-worker и невыбранный неудачный стек `repaircalc_prod`
автоматически не останавливаются и не удаляются; их обслуживание — отдельная операция.
Другие environments старый `repaircalc` не подхватывают. Неизвестный владелец порта,
непроверенный volume, смена major PostgreSQL или недоступная БД приводят к понятной
ошибке без остановки владельца порта. Если legacy уже работает на другом порту,
автоматическое создание второго `prod` с новой БД также блокируется: используйте
его текущий порт либо отдельно согласуйте перенос данных.

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
- Роль делает локальный backup существующей БД перед обновлением, но отдельно
  храните проверенную off-host копию. Flyway/init-data могут
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
- Команды с секретами и сырой вывод Compose скрыты через `no_log`.
  При сбое роль печатает безопасную сводку: статусы/health контейнеров, exit code,
  OOM и обнаруженные признаки (`port_conflict`, `bootstrap_failed`, ошибки БД и т. п.).
  Сырые данные сохраняются **только на стенде** в каталоге упавшего релиза:
  `deployment-failure.json` (ограниченный вывод исходной ошибки) и
  `deployment-diagnostics.json` (состояния и последние 80 строк логов каждого
  базового сервиса, максимум 64 KiB на поток). Файлы имеют права `0600`, могут
  содержать секреты и не загружаются в Actions artifacts. Путь указан в сводке.
  Это диагностика, а не автоматическое исправление причины или откат.
  Для ручной диагностики используйте на стенде `docker compose` с обоими манифестами
  из каталога нужного релиза (упавший релиз не обязательно `current`), с обоими
  `--env-file`, проектом из `deployment-target.json`/`compose-project.json` и
  `IMAGE_PREFIX`/`IMAGE_TAG` нужного релиза. Логи ограничивайте
  `logs --no-color --tail 80 <service>`; проверяйте их перед публикацией.

### Если PostgreSQL healthy, а backend сообщает об ошибке пароля

`pg_isready` и успешный `psql -h 127.0.0.1` не проверяют пригодность пароля для
подключения backend: localhost в стандартном образе может использовать `trust`,
а подключения из Docker-сети — `scram-sha-256`. Preflight проверяет именно
bridge-адрес PostgreSQL и не делает fallback на localhost.

`POSTGRES_PASSWORD` в окружении контейнера применяется при создании новой БД,
но **не меняет пароль роли в существующем volume**. Совпадение env у PostgreSQL
и backend тоже не подтверждает корректность пароля. В GitHub Environment задайте
`DB_PASSWORD`, соответствующий действующей роли БД. Не удаляйте volume и не
восстанавливайте учебную БД для устранения ошибки аутентификации.

Если неудачный старый деплой уже заменил контейнеры, сначала согласуйте восстановление
runtime-конфигурации с действующими реквизитами и повторный старт сервисов. Это
отдельная операция; preflight не извлекает пароль из других контейнеров и не делает
`ALTER ROLE` автоматически. Ротация пароля требует обновления всех клиентов БД,
включая уже работающий classifier-worker, и отдельного плана отката.

### Если конфликтует порт старого и нового стека

Не останавливайте чужой frontend автоматически. Проверьте на сервере:

```bash
docker ps -a --filter label=com.docker.compose.service=frontend --format 'table {{.Names}}\t{{.Ports}}\t{{.Status}}'
docker inspect repaircalc_prod-frontend-1 --format '{{.State.Error}}'
```

Если это здоровый legacy `repaircalc`, новый CD для `prod` подхватит его на
текущем порту автоматически, сохранив БД, как описано выше. Ничего останавливать
вручную или менять порт на `8081` для этого не требуется.

`repaircalc` и `repaircalc_prod` используют разные Compose volumes; успешный healthcheck
не доказывает, что каталог и admin-изменения перенесены. До переключения требуется
backup и отдельный план переноса актуальной БД, если вы сознательно выбрали
переход на другой проект, а не обновление текущего. Старую БД сохраните для отката;
`down -v` не выполняйте. `0.0.0.0:8080` тоже конфликтует с уже занятым
`127.0.0.1:8080`, поэтому смена только адреса на wildcard проблему не решает.

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
