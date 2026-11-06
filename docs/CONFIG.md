# Настройки

`env/.env` — обычные настройки, отслеживаемые Git; отдельного `.env.example` нет. Не помещайте сюда пароли. `env/secret.env.example` — допустимые имена секретов с пустыми значениями. `make init-config` создаёт секретный файл и локальный Compose override, добавляет отсутствующие ключи секретов. `make config-init` — алиас. Существующие значения секретов сохраняются; устаревшие ключи сначала архивируются в игнорируемый `env/secret.env.legacy` (mode 600).

Для локального Java/Python применяется порядок: `env/.env` → необязательный игнорируемый `env/.env.local` → `env/secret.env` → окружение процесса. При переходе прежний локальный `.env` сохранён как `.env.local`, без публикации его содержимого. `scripts/local.mjs` запускает дочерние процессы без shell и без секретов в аргументах. Python не получает Mongo/admin-реквизиты; Java не получает root-пароль Mongo и ключ replica set.

Make передаёт Compose `.env`, затем `.env.local`, если он есть, затем `secret.env`; переменные процесса имеют приоритет. CD использует отслеживаемый `.env` и GitHub Variables, но не локальный override. Внутренние адреса контейнеров заданы Compose отдельно от адресов локальных сервисов. Vite читает обычные env-файлы из `env/`, а `secret.env` не открывает. Секреты и локальные override исключены из Git и Docker build context.

## Группы

- `MONGO_*`: база, replica set, прикладной и root-пользователи, порты, таймауты, версия образа и ресурсы. `MONGO_URI` локального Java должна содержать database и быть без credentials; пароль задаётся отдельно.
- `ADMIN_*`: имя, PBKDF2-хеш, срок сессии, secure cookie и лимит тела. Для loopback HTTP требуется `ADMIN_COOKIE_SECURE=false`. Для HTTPS оставьте true.
- `LLM_*`, `INTERNAL_SERVICE_TOKEN`: URL внутреннего Python, таймауты, лимиты тела/ответа и параллелизм. Токен не короче 32 символов нужен при включении LLM.
- `OLLAMA_*`: URL, модель, inference timeout, context, число токенов, потоки, память/CPU и тег. `LOCAL_OLLAMA_URL` используется локальным launcher; контейнер использует `OLLAMA_URL`.
- `CLASSIFICATION_*`: включение диспетчера, попытки, опрос, аренда, heartbeat и пауза повтора. Heartbeat должен быть минимум вдвое короче аренды.
- `IMAGE_*`, `INSPIRATION_*`: ограничения изображений, размер превью и страницы. Пути Java переопределяются `CLASSIFICATION_TAXONOMY`, `INSPIRATIONS_DIR`, `INSPIRATIONS_MANIFEST`; Python — `DATA_DIR`, `PLAN_DATA_DIR`, `CLASSIFICATION_PROMPT`, `CLASSIFICATION_TAXONOMY`.
- `FRONTEND_*`, `API_*`, `DEV_*`: публикация frontend, nginx proxy, dev server. `VITE_*` — публичные build-time параметры, в том числе browser timeouts.
- `*_IMAGE`, `*_IMAGE_TAG`, `*_CPU_LIMIT`, `*_MEMORY_LIMIT`, `HEALTH_*`: образы, ресурсы и healthchecks. `MAVEN_CENTRAL_URL` позволяет использовать доступное публичное зеркало при сборке Docker.

Формулы, состав комплектов, тарифы и нормы находятся в версионированных `data/*.json`. Они не являются переменными окружения.

## Гайд и стартовый экран

В GitHub **Settings → Secrets and variables → Actions → Variables → Repository variables**:

| Variable | Default | Выключить |
| --- | --- | --- |
| `VITE_GUIDE_ENABLED` | `true` | `false` — не показывать гайд и кнопку его повторного запуска |
| `VITE_SPLASH_ENABLED` | `true` | `false` — не показывать белый стартовый экран с логотипом |

Это публичные **build-time** настройки: CI передаёт их в Docker build. После изменения запустите новую сборку/публикацию и разверните её SHA. Изменение только CD Environment или runtime env уже собранного nginx-образа не меняет интерфейс. Локально значения читаются из `env/.env` / `.env.local`; Compose передаёт их как build args.

Гайд показывается после стартового экрана при первом пользовательском посещении (не в админке). Завершение, крестик или Esc сохраняют cookie `repaircalc_guide_v1=done` на год, Path `/`, SameSite=Lax; при HTTPS — Secure. Cookie не содержит персональных данных. «Помощь → Повторить знакомство» запускает гайд снова. При запрете cookies браузером повторное посещение может снова показать гайд.

`DATA_DIR` задаёт общий каталог данных. `CATALOG_FILE`, `CATALOG_MAPPINGS_FILE`, `RULES_FILE`, `CLASSIFICATION_TAXONOMY`, `CLASSIFICATION_PROMPT`, `PLAN_DATA_DIR`, `INSPIRATIONS_DIR`, `INSPIRATIONS_MANIFEST` переопределяют отдельные пути; пустое значение использует путь внутри `DATA_DIR`. В локальном launcher относительные пути отсчитываются от корня репозитория, в контейнере — от `/app`. Для внешних данных Docker дополнительно нужен bind mount. `MONGO_TRANSACTION_MAX_ATTEMPTS` ограничивает повторы транзакции с явной transient-меткой Mongo (1–10, по умолчанию 3).

## Секреты

Задайте независимые `MONGO_ROOT_PASSWORD`, `MONGO_PASSWORD`, `MONGO_KEYFILE_SECRET`. Base64-ключ можно получить командой `node -e "console.log(require('crypto').randomBytes(48).toString('base64'))"`. Для LLM задайте `INTERNAL_SERVICE_TOKEN`. Хеш admin-пароля создаётся `python scripts/admin-password-hash.py` интерактивно, без пароля в argv.

Старые DB_PASSWORD/DB_URL/POSTGRES_* больше не используются. Конфигуратор обнаруживает прежний DB_PASSWORD и сообщает о ручном переносе. Пароли уже созданных Mongo-пользователей меняются отдельной операцией MongoDB: изменение init env не выполняет ротацию существующего тома.

CD проверяет Mongo-реквизиты по Docker bridge до рестарта, сохраняет привязку project/volume и делает backup перед обновлением. Инициализированная публикация не заменяется seed-каталогом: `expand-catalog` атомарно дополняет её отсутствующими 100 вариантами, сохраняя все текущие карточки, назначения, тарифы и комплекты. Автоматическое принятие старого стека допустимо только для Mongo; PostgreSQL требует отдельного нового размещения без удаления прежних данных. См. [RELEASE.md](RELEASE.md).

Mongo image закреплён patch-версией и digest. `MONGO_COMPAT_SECONDS` (91–600, default 95) и `MONGO_COMPAT_START_TIMEOUT_SECONDS` (10–300, default 60) настраивают совместимость CI/CD. На потенциально затронутом ядре Docker перед деплоем выполняется отдельный probe, не использующий рабочий volume. Подробности: [CI_RUNTIME.md](CI_RUNTIME.md).
