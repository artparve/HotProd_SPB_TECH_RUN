# Проверка frontend + Java backend без LLM

Дата: 24.10.2026, Europe/Moscow. Checkout: `/home/artparve/projects/spb_tech_run_2026`, ветка `backend`, remote `git@github.com:artparve/HotProd_SPB_TECH_RUN.git`.

## Результат

**Связка подтверждена для каталога и калькулятора отдельного товара. Полный пользовательский цикл общей сметы и подбора пока не работает в API-режиме.**

Проверены настоящие запросы по цепочке браузер → nginx frontend → Spring MVC → PostgreSQL. Фронтенд пересобран с `VITE_DATA_MODE=api`; подмены API демоадаптером нет. Каталог, цены и нормы — предоставленные тестовые fixtures, а не реальные товарные данные.

LLM/Ollama и Python-классификатор в рамках проверки не запускались; запросы к моделям не отправлялись. Во всех 11 успешных ответах проверены `mode=api` и `versions.model=not-used`. Служба Java возвращает `not-used` непосредственно в `ProductCalculationService`.

## Автоматические проверки

| Проверка | Результат |
| --- | --- |
| `make test`, frontend Vitest | 36 passed, 4 файла |
| `./mvnw verify`, Java unit/API/config | 80 passed, 0 failures/errors/skipped |
| Тот же verify, PostgreSQL Testcontainers IT | 60 passed, 0 failures/errors/skipped |
| Ответы Maven IT через существующие frontend Zod-схемы | 12 карточек и 11 расчётов приняты |
| Live HTTP через nginx и прямой API | 28 проверок passed |
| Live ответы через существующие frontend Zod-схемы | 12 карточек и 11 расчётов приняты |
| Production frontend build внутри Docker | TypeScript и Vite build passed |

Подсчёт Java-тестов взят из свежих `target/surefire-reports/TEST-*.xml` и `target/failsafe-reports/TEST-*.xml`; Maven завершился с exit 0 и `BUILD SUCCESS`. Это проверки Java API, не Python/LLM-модуля. Существующий Playwright demo-suite не запускался как доказательство API-интеграции: его конфигурация явно включает `VITE_DATA_MODE=demo`.

Live проверки включают liveness/readiness, nginx health, совпадение прямого и проксированного каталога, 11 независимых эталонов количества/потребности/остатка/денег, строгий frontend-контракт, JSON content type и UUID `X-Request-ID`. Также проверены 400 для повреждённого JSON, 422 для отсутствующих/неверных полей, дробных слоёв и розеток, отказ неполной карточки, 404 неизвестного UUID/демоидентификатора, 405 и 415. Отсутствующие `/estimate` и `/recommendations` подтверждены как 404; это зафиксированные ограничения, а не успешно реализованные функции.

Артефакты: [результаты HTTP](checks.json), [каталог API](products-live.json), [11 расчётов API](estimates-live.json), [frontend tests](frontend-tests.log), [Maven verify](backend-verify.log), [скрипт live-проверки](live-check.mjs). В скрипте эталоны продублированы явно из ручных примеров `ProductCalculationApiIT`, без вызова расчётной реализации для вычисления ожиданий.

## Браузерная проверка

1. После перезагрузки фронтенд показывает `REST API` и получает 12 карточек с UUID и источником тестового каталога.
2. Плитка `ac1f222a-f5e9-3b5d-a91e-c943b053e92e`, площадь 20 м² и запас 10%: потребность 22 м², **16 упаковок**, куплено 23,04 м², остаток 1,04 м², материалы/итого **34 560,00 ₽**, работы 0,00 ₽. Формула и версии разворачиваются, модель — `not-used`.
3. Лак без подтверждённой нормы: расчёт не появляется, показана ошибка HTTP 422.
4. Общий расчёт → «Подобрать материалы»: HTTP 404, следующий этап не открывается.
5. Подбор для комнаты → «Найти материалы»: HTTP 404, подбор не появляется.

Подтверждения: [расчёт API](product-api.png), [общая смета 404](estimate-404.png), [подбор 404](recommendations-404.png), [неполная карточка 422](blocked-product.png).

## Ограничения и проблемы

### 1. Общая смета и подбор пока не подключены полностью

Frontend вызывает `POST /api/v1/estimate` и `POST /api/v1/recommendations` в `frontend/src/services/api.ts:50` и `:54`. Java реализует только `GET /api/v1/products` и `POST /api/v1/product/{id}/calc`. Ошибки подтверждены и live HTTP, и нажатием соответствующих кнопок в UI. Это соответствует текущему объёму в `backend/docs/TEAM_SETUP.md`; для завершения трёх пользовательских сценариев нужны отсутствующие API.

### 2. Предупреждение о неизвестном товаре остаётся после корректного выбора

В API-режиме открыть прежнюю ссылку `/product?id=demo-laminate`, затем выбрать плитку из списка и рассчитать. URL уже содержит существующий UUID, расчёт возвращается с 200, но сообщение «Товар по ссылке не найден» остаётся. Причина: `invalidProduct` задаётся только в `load()` (`frontend/src/views/ProductView.vue:35`) и не обновляется при смене выбранного товара (`:48`). Старые `demo-*` ID отсутствуют в Java-каталоге; исходное предупреждение ожидаемо, сохранение предупреждения после выбора — ошибка UI. [Скриншот](stale-product-warning.png).

### 3. UI теряет объяснение серверного отказа

Для лака сервер возвращает JSON с причиной отсутствия нормы и полями уточнения, однако `frontend/src/services/api.ts:24` заменяет любой неуспешный ответ общим сообщением «Проверьте подключение или повторите запрос». В браузере показано только HTTP 422, причина не выводится в области ошибки. Аналогично пользователь не получает объяснения отсутствующих функций при 404. Следует отображать безопасные `ApiError.message`/`fields` и подходящее действие; обмен данными при этом работает корректно.

## Запущенное окружение и изменения

На хосте не было JDK. Для сборки, тестов, импорта и API использован официальный `eclipse-temurin:21-jdk`; для проверок TypeScript-контрактов — официальный `node:22-alpine`. Системный Node 22.22.1 возвращает `ERR_NO_TYPESCRIPT` при `--experimental-strip-types`, поэтому штатные contract-скрипты в нём не выполняются; в официальном Node они прошли без изменения схем или скриптов проекта.

| Контейнер | Назначение | Адрес/состояние |
| --- | --- | --- |
| `repaircalc-frontend-1` | nginx + production frontend, API mode | `http://127.0.0.1:8080`, healthy |
| `repaircalc-backend-check` | Java API | `http://127.0.0.1:8000`, readiness UP |
| `repaircalc-postgres-check` | PostgreSQL 17.11 | Docker network, healthy, наружу порт не опубликован |

Контейнеры находятся в `repaircalc_default`; frontend использует `DOCKER_API_UPSTREAM=http://repaircalc-backend-check:8000`. Данные отдельной тестовой БД сохраняются в `repaircalc-pgdata-check`. Случайные локальные DB-credentials находятся в `/tmp/repaircalc-integration/runtime.env` с mode 0600; содержимое не включено в отчёт. Окружение проверки не заменяет недостающую общую Compose-интеграцию: `make up` по-прежнему управляет только frontend.

Изменён только игнорируемый `env/.env` (demo → api и адрес upstream), frontend пересобран. Предыдущая конфигурация сохранена в `/tmp/repaircalc-integration/frontend.env.before`. Исходники приложения и существующие изменения пользователя не редактировались. Добавлена только папка этого отчёта; Maven `backend/target` игнорируется Git. Стенд оставлен работающим, финальная вкладка открыта на расчёте плитки.

Остановить стенд без удаления контейнеров или тома:

```bash
docker stop repaircalc-frontend-1 repaircalc-backend-check repaircalc-postgres-check
```

Повторно запустить сохранённые контейнеры: сначала PostgreSQL, затем Java API, дождаться readiness UP, затем frontend. У Java и PostgreSQL в этом проверочном запуске не задан автоматический restart. Файлы в `/tmp` временные; не рассчитывать на их сохранение после перезагрузки. Чтобы вернуть прежний demo-frontend, восстановить сохранённый env и пересобрать через `make up DOCKER='docker --config /tmp/repaircalc-docker-config'`. Compose/Buildx используются из `/tmp`, как при предыдущем запуске в этом чате.

Повторить live-проверку при работающем стенде (обновляет JSON-артефакты отчёта):

```bash
docker run --rm --network host --user 1000:1000 \
  --mount type=bind,src=/home/artparve/projects/spb_tech_run_2026,dst=/home/artparve/projects/spb_tech_run_2026 \
  --workdir /home/artparve/projects/spb_tech_run_2026 \
  node:22-alpine node reports/integration/2026-10-24/live-check.mjs
```

Экспорт JSON, локальное сохранение смет/корзины и мобильная раскладка не проверялись отдельными браузерными сценариями этой проверки. Результат не подтверждает реальные цены/нормы, полный каталог, ML-качество или несуществующие API общей сметы и подбора.
