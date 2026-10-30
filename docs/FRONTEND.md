# Frontend RepairCalc

Для последовательной инструкции «заполнить переменные → собрать → запустить Docker → проверить» используйте **[deploy.md](deploy.md)**.

## Один уровень управления

**Корневой Makefile — единственная точка управления проектом.** Его рецепты напрямую вызывают npm и Docker Compose, без промежуточного `manage.py` или Python-обёрток.

Отдельных скриптов и папки `scripts/` нет. Команда `make init-config` создаёт отсутствующие конфиги непосредственно по файловым правилам Makefile. Для одинакового копирования на всех ОС внутри рецепта используется короткая команда `node -e` со стандартным `fs`, без Unix-команды `cp` и Windows-команды `copy`. Существующие настройки не перезаписываются.

```text
Makefile         все команды разработки, проверки, сборки и Docker
env/.env.example общий шаблон настроек, хранится в Git
env/.env         локальные настройки, игнорируются Git
frontend/src/    страницы, компоненты, типы, API-адаптер и демодвижок
frontend/nginx/  SPA routing, healthcheck и API proxy
docker/         Compose, шаблон и локальный override
docs/           OpenAPI и пояснения
```

Стек приложения: Vue 3, TypeScript, Vue Router, Sass, Vite, Decimal.js, Zod. Нет внешних шрифтов, CDN или трекеров. Сметы сохраняются только в localStorage этого браузера.

## Windows, macOS и Linux

Общие требования:
- **GNU Make** (совместимо с GNU Make 3.81+).
- **Node.js 22.12+ LTS** или 24 LTS, с npm; `.nvmrc` содержит 22.
- Для Docker-команд — работающий Docker Engine и Compose v2.

Для локального запуска через Node.js Python, Bash, MSYS2, WSL и PowerShell-скрипты не требуются. Для Docker Desktop на Windows с Linux-контейнерами нужен WSL 2; см. [установку Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/). У Makefile нет жёстко заданного `SHELL`, Windows-путей, `.cmd`, `os.name`, Unix-команды `export` или присваивания env в стиле `VAR=value command`.

GNU Make нужно установить отдельно, если команды `make` нет:
- **Windows:** `winget install --id ezwinports.make --exact --scope user` или `choco install make` при установленном Chocolatey. `nmake` из Visual Studio не является заменой GNU Make.
- **macOS:** GNU Make доступен с Command Line Tools; также можно установить `brew install make`. Homebrew может назвать команду `gmake` — тогда используйте `gmake` вместо `make`.
- **Linux:** пакет `make` из пакетного менеджера дистрибутива, например `sudo apt install make`.

Если после установки PowerShell сообщает, что `node` или `make` не распознаны, обновите PATH текущего терминала:

```powershell
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
node --version
make --version
```

Встроенный терминал IDE наследует окружение самой IDE: открытие новой вкладки может сохранить старый PATH. Обновите его командой выше либо полностью перезапустите IDE.

Если установлен GNU Make под именем `gmake` или `mingw32-make`, это тот же формат Makefile. Вложенные вызовы используют `$(MAKE)`, поэтому продолжают работать с выбранным именем команды. Из каталога проекта:

```bash
make init
make dev
# http://127.0.0.1:5173
```

Пути проекта могут содержать пробелы: рецепты используют относительные пути от корня, а вложенные вызовы GNU Make заключены в кавычки.

## Команды

### Подбор по изображению комнаты

На главной `/` добавлена горизонтальная лента интерьеров. Наведение мыши показывает
увеличенное изображение; клик на изображение открывает крупный просмотр с закрытием
по Escape. На телефоне и с клавиатуры используйте кнопку «Рассмотреть».

«Выбрать интерьер» отправляет ID изображения и текущий проект на backend. После
проверки ответа, каталога и обычного расчёта открывается `/calculator` на шаге выбора
материалов: товары уже выбраны, но их можно заменить и пересчитать. Подбор относится
к первой комнате проекта; размеры не извлекаются из фотографии. Сообщения backend
показываются над калькулятором. Ошибка, отмена, уход со страницы или изменение проекта
во время ожидания не применяют запоздалый результат.

- `VITE_DATA_MODE=demo`: локальные SVG-иллюстрации и явно обозначенные фикстуры,
  без ML, сетевых запросов и настоящего распознавания.
- `VITE_DATA_MODE=api`: лента и подбор поступают только от backend, без fallback.
  Новые `/room-inspirations` и `/room-inspirations/{id}/analyze`, а также `/estimate`
  требуют серверной реализации; в этой задаче меняются только frontend и контракт.
- URL изображений должны быть same-origin, например `/api/v1/room-inspirations/123/image`.
  Внешние URL не принимаются и не требуют ослабления CSP.
- Ожидание распознавания — до 120 секунд; Vite/nginx proxy настроены на 125 секунд.
- Обновление страницы сохраняет параметры проекта, но не временный подбор: выберите
  изображение повторно. Результат можно сохранить штатной кнопкой сохранения сметы.

Моки: `frontend/src/data/inspirations.ts` (учебный адаптер) и
`frontend/tests/mocks/inspirations.ts` (перехват HTTP в Playwright). Проверки:

```bash
make check
make e2e
# Только новые desktop/mobile-сценарии, API полностью замокан:
npm --prefix frontend run test:e2e:inspirations
```

Для API-тестов автоматически запускается отдельный Vite на порту 5174; backend,
Docker и модель не нужны. Обычные demo-сценарии используют порт 5173.

| Команда | Действие |
| --- | --- |
| `make help` | Список задач, без установки зависимостей |
| `make init-config` | Создать отсутствующие env и локальный Compose override, не перезаписывая имеющиеся |
| `make init` | Создать конфиги и выполнить `npm ci` |
| `make install` | Только `npm ci` по lockfile |
| `make dev` | Vite dev server на localhost:5173 |
| `make build` | Проверка TS и сборка в `frontend/dist` |
| `make preview` | Локальный просмотр уже собранного frontend |
| `make format` | Форматирование frontend с Prettier |
| `make typecheck` | Проверка TypeScript |
| `make test` | Unit/component/API/OpenAPI-тесты Vitest |
| `make check` | Форматирование, TS, все unit/component/contract-тесты и production build |
| `make e2e-install` | Первичная загрузка Playwright Chromium |
| `make e2e` | Браузерные desktop/mobile-сценарии |
| `make up` | Compose build + запуск с ожиданием healthcheck |
| `make down` | Остановка Compose без удаления томов или образов |
| `make logs` | Последние 100 строк логов и дальнейшее наблюдение |
| `make ps` | Состояние контейнеров |
| `make config` | Проверка Compose без запуска контейнеров |
| `make image` | Сборка Docker-образа с текущим `IMAGE_TAG` из `env/.env` |
| `make release` | Последовательно `npm ci` → проверки → production build |
| `make release-image` | Релиз, затем сборка Docker-образа |

`release` гарантирует завершение `npm ci` до чтения зависимостей тестами, в том числе при `make -j`. Установка не запускается параллельно с проверками внутри релизной задачи. Не запускайте отдельный `make install` в другом процессе во время dev server или тестов.

`NODE`, `NPM` и `DOCKER` можно переопределить стандартными переменными Make, например `make check NPM=npm`. Обычно это не требуется. Тег образа задаётся только в `env/.env`, а не отдельным дублирующим аргументом `VERSION`.

## Единый env/.env

```dotenv
VITE_DATA_MODE=demo
VITE_API_BASE_URL=/api/v1
API_UPSTREAM=http://127.0.0.1:8000
DOCKER_API_UPSTREAM=http://host.docker.internal:8000
FRONTEND_HOST=127.0.0.1
FRONTEND_PORT=8080
IMAGE_TAG=local
```

| Переменная | Назначение |
| --- | --- |
| `VITE_DATA_MODE` | `demo` — учебный адаптер; `api` — backend без fallback на фикстуры |
| `VITE_API_BASE_URL` | Префикс браузерных запросов; рекомендуется `/api/v1` |
| `API_UPSTREAM` | Адрес backend для локального Vite на хосте |
| `DOCKER_API_UPSTREAM` | Старый параметр ручного запуска nginx; полный Compose использует `backend:8000` |
| `ESTIMATION_UPSTREAM` | Адрес Python estimation-сервиса для Vite, по умолчанию `http://127.0.0.1:8010` |
| `DOCKER_ESTIMATION_UPSTREAM` | Старый параметр ручного запуска; полный Compose использует `estimator:8010` |
| `COMPOSE_DATA_MODE` | Режим сборки frontend в Compose, по умолчанию `api` |
| `DB_PASSWORD` | Пароль отдельной PostgreSQL в полном Compose |
| `FRONTEND_HOST` | Интерфейс публикации Docker-порта, по умолчанию localhost |
| `FRONTEND_PORT` | Порт frontend на хосте |
| `IMAGE_TAG` | Тег Docker-образа для Compose, по умолчанию `local` |

Vite настроен на папку `env/`; Compose получает тот же файл через явный `--env-file env/.env`. Makefile **не включает env как Make-код и не разбирает его сам**: каждый инструмент использует штатный dotenv-механизм. Переменные окружения процесса имеют приоритет над файлом.

`127.0.0.1` внутри контейнера — сам контейнер, не компьютер разработчика. Поэтому для backend на хосте используются разные `API_UPSTREAM` и `DOCKER_API_UPSTREAM`. Для одного удалённого сервера можно указать одинаковый адрес, например `http://10.0.0.5:8000`, в обеих переменных. Адрес — origin без завершающего `/`.

Для локального Vite поставьте `VITE_DATA_MODE=api` и задайте адреса. Backend должен поддерживать [OpenAPI](openapi.yaml). После изменения env перезапустите `make dev`. Полный Compose использует `COMPOSE_DATA_MODE=api` и внутренние адреса сервисов; после изменения настроек выполните `make up`.

В карточке товара в API-режиме включено «Добавить пояснение ИИ». Запрос идёт в отдельный
[Python estimation-сервис](../backend/estimation/README.md): Ollama вызывает расчётный tool,
тот обращается к Java, затем модель поясняет результат. Суммы интерфейс берёт из проверенной
Java-сметы `estimate`, а текст модели показывает отдельно с предупреждением. Снятие флажка
запускает прежний Java-расчёт без Ollama. При ошибке ИИ-сервиса автоматической подмены нет.
Для локальной разработки запустите сервис на `127.0.0.1:8010`; полный Compose запускает
его автоматически. Endpoint `/api/v1/materials/` имеет отдельный
proxy и увеличенный таймаут: на CPU первый ответ может занять несколько минут.

`VITE_*` публичны и встраиваются в JavaScript при сборке. Секреты там хранить нельзя. Редактирование env не меняет уже собранный JavaScript. Дополнительные `.env.local`/`.env.<mode>` в папке `env/` штатно поддерживаются Vite, но проект их не создаёт: для единой конфигурации используйте только `env/.env`.

## Docker

```bash
make init-config
# Задайте собственный DB_PASSWORD в env/.env перед первым запуском.
make config
make up
# http://127.0.0.1:8080
```

Node нужен для автоматического создания конфигов, но **не для запуска уже собранного контейнера**. На Docker-only хосте без Node можно вручную скопировать `env/.env.example` в `env/.env`, а `docker/docker-compose.override.yml.example` в `docker/docker-compose.override.yml`. Если оба файла существуют, `make up`, `make config` и `make image` не выполняют копирование и не требуют Node.

Порт frontend публикуется в игнорируемом `docker/docker-compose.override.yml`. Базовый Compose не публикует порты хоста. Он запускает frontend, Java, PostgreSQL, импорт учебных данных и Python estimator; Ollama доступна через профиль `llm`, классификатор — `batch`. Подробности и команды — в [инструкции полного стека](deploy.md).

Build context — `frontend/`: env, большие каталоги и документы не отправляются Docker daemon. Публичные параметры передаются явно через build args. Образ: Node 22 → непривилегированный nginx, read-only root FS и writable `/tmp`. `/healthz` проверяет nginx, не backend.

В старом локальном override может оставаться `host.docker.internal:host-gateway`. Полный стек использует DNS-имена сервисов в общей сети Compose.

nginx сохраняет `/api/...` и не возвращает HTML SPA вместо ошибок API. CSP допускает same-origin подключения. TLS, внешнее iframe-встраивание, production CORS и авторизация настраиваются отдельно.

Прямой эквивалент `make up` на всех трёх ОС:

```bash
docker compose --project-directory docker --env-file env/.env -f docker/docker-compose.yml -f docker/docker-compose.override.yml up --build --detach --wait
```

## Проверки и результат релиза

```bash
make check
make e2e-install
make e2e
make release
```

Vitest проверяет UI, расчёты, API-адаптер, структуру и ссылки OpenAPI и наборы полей OpenAPI/Zod. Межполевые ограничения также требуют серверной проверки. Playwright проверяет desktop/mobile, смету, альтернативы, корзину, JSON, неверные данные, отсутствие внешних запросов и повреждённый localStorage.

Релиз создаёт **только `frontend/dist`**, с текущим `env/.env`, без принудительного переключения в demo. `make release-image` дополнительно собирает образы приложений полного Compose с `IMAGE_TAG` из этого файла. Ни архив исходников, ни SHA256, ни Git-коммиты, теги, публикация или деплой не выполняются.

Установка npm и сборка Docker требуют registry. Уже собранный demo-frontend автономен. Реальные цены, наличие, качество ML и технологическая полнота смет не имитируются как подтверждённые данные.
