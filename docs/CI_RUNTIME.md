# MongoDB в CI и проверка совместимости стенда

Mongo-задания `backend` и `stack` временно используют `ubuntu-22.04`: MongoDB 8.0 на новых Ubuntu 24.04 runner images завершался до тестов с fatal `12257600`. Возврат на 24.04 выполняется отдельным проверенным изменением после исправления upstream. Release gate остаётся привязан к успешному push CI выбранного SHA в `main`.

## Образ и настройки

Канонический default — `MONGO_IMAGE_TAG` в `env/.env.example`: MongoDB 8.0.34 с digest многоархитектурного manifest. Compose и CD содержат тот же fallback; release-тест проверяет согласованность. Java Testcontainers читает этот tracked-файл, не локальные env/секреты, либо явный `MONGO_IMAGE_TAG`. Для Testcontainers ссылка нормализуется в `mongo@sha256:...`, поскольку его parser не обрабатывает сочетание `name:tag@digest` как обычное имя `mongo`.

Параметры проверки:

- `MONGO_COMPAT_SECONDS=95`: 91–600 секунд непрерывной работы после готовности и проверки транзакции.
- `MONGO_COMPAT_START_TIMEOUT_SECONDS=60`: 10–300 секунд на каждый этап готовности.
- Явный `MONGO_IMAGE_TAG` переопределяет default. Существующие пользовательские env и GitHub vars не переписываются автоматически; старый override `8.0` останется плавающим, пока оператор не заменит его на проверенный tag/digest.

## Локальные команды и CI

```sh
python scripts/mongo_runtime.py diagnose --report .build-mongo-environment.json
python scripts/mongo_runtime.py probe --report .build-mongo-compat.json
```

Диагностика содержит kernel/architecture/version Docker daemon, client kernel/signature, runner image/version, Compose и image ID/digests. Client kernel не подменяет kernel удалённого Docker daemon.

Probe запускает новый контейнер с `--network none`, без публичных портов, прикладных реквизитов и постоянных volumes. `/data/db`, `/data/configdb` и `/tmp` находятся в tmpfs. Внутри создаётся одиночный replica set, выполняется настоящая транзакция, затем ping/CRUD в течение заданного времени. Проверяется состояние процесса и отсутствие рестартов. Удаляется только контейнер с созданной этой проверкой меткой; имя сохраняется в отчёте для диагностики ошибки очистки.

`backend` запускает probe перед Maven. `verify-stack.py` запускает его до сборки приложения и чтения/создания test stack. У базового прогона отдельный отчёт. При ошибке CI сохраняет `.build-mongo-*.json` вместе с соответствующими Java/Playwright артефактами. Лог ограничен последними 80 строками нового probe-контейнера; `Config.Env` и подставленная конфигурация с секретами не выводятся.

## CD

Ansible устанавливает автономный `mongo_runtime.py` рядом с backup utility. Preflight перед login/backup/рестартом вызывает:

```sh
python scripts/mongo_runtime.py remote-check --image mongo:<tag-or-digest> --report mongo-compatibility.json
```

На Docker daemon с ядром ниже 6.19 проверка фиксирует совместимое ядро без дополнительного запуска Mongo. На новом или неопределённом ядре требуется полный изолированный probe выбранного бинарника. Поэтому kernel controller не определяет результат проверки стенда, а Ubuntu upstream signature не трактуется вручную как разрешение обходить защиту Mongo.

При `mongodb_kernel_incompatible`, exit процесса, неготовом replica set, ошибке транзакции или позднем падении preflight запрещает запуск release images. Рабочие сервисы и volumes проверка не меняет. Результат остаётся в закрытом `mongo-compatibility.json`; `deploy_diagnostics.py` распознаёт kernel failure и выводит безопасный тип ошибки. Оригинальные логи живых сервисов остаются в закрытой диагностике стенда.

## Подтверждение

Локальная проверка выбранного digest прошла на Docker daemon kernel `6.18.54.1-microsoft-standard-WSL2`: replica set, транзакция, 95 секунд CRUD и очистка контейнера. Это не подменяет проверку GitHub runner или production host.

Первый исправленный push `85b597d` подтвердил выбор runner: [CI 41172067509](https://github.com/artparve/HotProd_SPB_TECH_RUN/actions/runs/41172067509) успешно выполнил backend, frontend, Python, stack и публикацию трёх образов. Итоговые run IDs фиксируются в результате выполнения задачи после проверки версии с compatibility gate.

Исходный анализ и upstream-источники: [CI_CD_FIX_PLAN.md](CI_CD_FIX_PLAN.md).
