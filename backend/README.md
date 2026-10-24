# RepairCalc Java backend

Самостоятельный Spring MVC backend. Базовая версия Java — 21; preview features не используются.

**Для другого участника команды:** [пошаговый запуск на Windows/Linux/macOS](docs/TEAM_SETUP.md) — требования, PostgreSQL, импорт каталога/правил, запуск, проверка API и повторный старт с сохранённой БД. Глобальный Maven/Make/Python/Node для Java backend не нужны; Node требуется только дополнительным frontend contract checks.

## Сборка

Из каталога `backend`:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21' # Укажите свой установленный JDK 21.
.\mvnw.cmd verify
```

Linux/macOS: `./mvnw verify`. Wrapper загружает фиксированную версию Maven; первая сборка требует доступа к Maven Central. Установленный глобально Maven не требуется.

## Конфигурация и health

По умолчанию включён профиль `local`, адрес `127.0.0.1:8000` соответствует существующему Vite proxy. Профиль `deploy` слушает `0.0.0.0:8000`. Адрес и порт переопределяются через `SERVER_ADDRESS` и `SERVER_PORT`; профиль — через `SPRING_PROFILES_ACTIVE`. Примеры находятся в `.env.example`: Spring Boot не читает `.env` автоматически, переменные нужно передать процессу.

- `GET /actuator/health/liveness`: HTTP 200, если процесс работает.
- `GET /actuator/health/readiness`: HTTP 200 при доступной БД и совместимой опубликованной паре каталога/правил; иначе 503. Доступность витрины проверяется независимо.
- В рабочей реализации BE-07 readiness также проверяет БД. Служебные env/config/beans endpoints отключены, детали health скрыты.

Профиль `test` находится только в test resources и использует случайный порт. Учётные данные и локальные `.env` не коммитятся.

## Ошибки API и request ID

Ответ ошибки соответствует `../docs/openapi.yaml`: `{"code":"...","message":"...","fields":["path"]}`. Пустой `fields` опускается. Некорректный JSON даёт 400, ошибки входной validation и `DomainException` — 422, неожиданные ошибки и нарушение контракта возвращаемого значения — безопасный 500. Стандартные статусы 404/405/415 сохраняются.

Каждый запрос получает новый серверный UUID в `X-Request-ID`; клиентский заголовок не используется как идентификатор. ID доступен в MDC и stdout-логах до завершения запроса, затем удаляется. Completion-лог содержит метод и HTTP-статус; URL, тело и параметры запроса не записываются.

## PostgreSQL и миграции — BE-07

Подготовлены JPA, PostgreSQL driver, Flyway и Testcontainers с версиями из Boot BOM. V1 создаёт схему `repaircalc`, V2–V4 — каталог, назначения работ и правила; история Flyway находится в `public`. Hibernate проверяет сущности через `ddl-auto=validate`, `open-in-view` отключён.

Для локальной БД выделен порт `55432`. Команды выполняются в PowerShell из `backend` после запуска Linux engine Docker:

```powershell
docker version
# Задайте собственный пароль в переменной DB_PASSWORD текущего процесса.
if (-not $env:DB_PASSWORD) { throw 'Set DB_PASSWORD before starting PostgreSQL' }
$env:POSTGRES_PASSWORD = $env:DB_PASSWORD
docker run --detach --name repaircalc-postgres --publish 127.0.0.1:55432:5432 --env POSTGRES_DB=repaircalc --env POSTGRES_USER=repaircalc --env POSTGRES_PASSWORD --mount source=repaircalc-pgdata,target=/var/lib/postgresql/data postgres:17.11
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55432/repaircalc'
$env:DB_USER = 'repaircalc'
.\mvnw.cmd spring-boot:run
```

Повторный запуск существующего контейнера: `docker start repaircalc-postgres`. Профиль `deploy` требует явные `DB_URL`, `DB_USER` и `DB_PASSWORD`. При недоступности БД во время запуска Flyway завершает запуск с ошибкой; потеря БД после старта отражается в readiness, liveness остаётся независимым.

`mvnw.cmd test` выполняет unit/API/config tests без БД. `mvnw.cmd verify` дополнительно запускает обязательные `*IT` через Maven Failsafe. Каждому PostgreSQL IT-классу принадлежит отдельный `postgres:17.11` контейнер; параметры подключения передаёт `@ServiceConnection`. Они проверяют схему/историю, повтор миграции, JPA-соединение, readiness при доступной БД и после её остановки. Неисправный Docker вызывает ошибку, тесты не пропускаются.

Для нестандартного Docker context перед `verify` можно передать его endpoint текущему Java-процессу:

```powershell
$env:DOCKER_HOST = (docker context inspect --format '{{.Endpoints.docker.Host}}').Trim()
.\mvnw.cmd verify
```

**Статус BE-07: готово.** `mvnw.cmd verify`: PASS, 19 unit/API/config tests и 5 интеграционных тестов на настоящем PostgreSQL 17.11, без пропусков. Docker CLI и сервер работают; миграция чистой БД, повтор миграции и потеря БД после запуска подтверждены.

Дополнительно проверен упакованный JAR в профиле `local` с отдельной PostgreSQL через Docker CLI: liveness 200, readiness 503 без опубликованных данных, тестовый маршрут 404, UUID в заголовке и stdout. Временные контейнер и Java-процесс после проверки остановлены.

## Подготовленный каталог — BE-09

В `data/catalog-v1.json` находятся 12 **тестовых** карточек с UUID, исходными записями и явными пометками учебных цен/свойств. `data/work-mappings-v1.json` содержит ручные тестовые назначения из `work-taxonomy-v1`. У обоев `packageSize=null`; ширина, длина и раппорт сохранены отдельно. Нормы из фикстур не являются нормативами производителя.

После запуска PostgreSQL и передачи DB-переменных выполните из `backend`:

```powershell
.\mvnw.cmd package
& "$env:JAVA_HOME/bin/java" -jar target/repaircalc-backend-0.1.0-SNAPSHOT.jar --repaircalc.command=import-catalog --repaircalc.catalog.file=data/catalog-v1.json --repaircalc.catalog.mappings=data/work-mappings-v1.json
```

Команда запускается без HTTP-сервера и закрывает контекст после выполнения. Успешная публикация и повтор завершаются с exit code 0; ошибка — с ненулевым кодом. Обычный запуск приложения данные автоматически не загружает.

Проверка упакованной команды через Docker CLI: публикация — exit 0, повтор — exit 0/no-op, отсутствующий файл — ненулевой exit; в БД остаются один release, 12 ревизий и одна активная версия. HTTP-сервер в CLI-режиме не запускается.

Каталог v1: `schemaVersion`, `version`, `dataClass="test"`, `products`. Карточки содержат поля Product без `works` и исходные `sources: [{namespace, externalId, raw}]`. Соответствия v1: `schemaVersion`, `version`, `taxonomyVersion`, `mappings: [{productId, works, evidence}]`.

Публикация сериализована PostgreSQL advisory lock и выполняется одной транзакцией. Опубликованные ревизии, исходные записи и назначения защищены от изменений триггерами. Повтор той же версии и fingerprint не создаёт дубли и не активирует старый release заново. Изменение каталога требует новой версии каталога, изменение файла назначений — новой версии назначений. Fingerprint учитывает SHA-256 байтов обоих файлов и версию импортера. Ошибка сохраняет предыдущую активную публикацию; внешний ID нельзя незаметно перепривязать к другому UUID.

## API витрины — BE-10

После публикации каталога запустите обычный backend: `.\mvnw.cmd spring-boot:run`. `GET http://127.0.0.1:8000/api/v1/products` возвращает массив Product по существующему OpenAPI. Сортировка — по UUID; цена — десятичная строка с двумя знаками. Неизвестные packageSize/wet/highLoad/unheated остаются `null`; неизвестные optional consumption/rollWidth/rollLength/rapport опускаются.

Запрос фиксирует один опубликованный release и читает только его неизменяемые ревизии/назначения; таксономия назначений берётся из его manifest. До публикации или при недоступности БД ответ — безопасный `503 {"code":"catalog_unavailable","message":"Catalog is temporarily unavailable."}`. Опубликованный пустой каталог даёт `200 []`. Отсутствие правил не блокирует витрину.

Полная проверка Java + реальных HTTP-ответов витрины и калькулятора существующими frontend Zod-схемами (нужны Node и установленные frontend dependencies), из `backend`:

```powershell
.\scripts\verify-contract.ps1
```

Скрипт удаляет только прежние `target/contracts/products-response.json` и `product-estimates-response.json`, выполняет обязательный Maven verify/PostgreSQL IT и проверяет свежие ответы через `catalog-contract.mjs` и `calculation-contract.mjs`. Витрина из 12 товаров принимается `productSchema`; 11 реальных расчётов принимаются `estimateSchema` с дополнительными проверками состава, одной строки, точных денежных итогов и упаковочных инвариантов. Ошибка Maven, отсутствие свежего ответа или несовместимость Zod завершают проверку с ошибкой. Отдельный `mvnw.cmd verify` сохраняет независимость Java-сборки от Node.

Проверка собранного JAR через Docker CLI: обычный старт не загружает данные (`503 catalog_unavailable`); после консольного импорта тот же запущенный сервер возвращает 12 товаров без перезапуска. Ответ принят frontend Zod-схемой, повтор импорта завершился exit 0/no-op. Liveness — 200, общая readiness — 503 до публикации правил; `X-Request-ID` присутствует. Временные PostgreSQL-контейнер и Java-процесс остановлены.

## Правила расчёта — BE-11

`data/rules-v1.json` — типизированный **учебный** bundle: точная версия/fingerprint каталога, источники, восемь профилей семи стратегий, 11 рассчитываемых назначений, явная блокировка неполного лака и пять тестовых тарифов работ. Цена и фасовка остаются в каталоге; нормы принадлежат правилам и проверяются на согласованность с заполненными нормами карточек.

После импорта каталога, с теми же DB-переменными, из `backend`:

```powershell
& "$env:JAVA_HOME/bin/java" -jar target/repaircalc-backend-0.1.0-SNAPSHOT.jar --repaircalc.command=import-rules --repaircalc.rules.file=data/rules-v1.json
```

Команда работает без HTTP-сервера и закрывает контекст. Повтор тех же байтов/версии — exit 0/no-op без повторной активации; изменение требует новой версии. Загрузка не выполняется автоматически при старте. V4 защищает опубликованный bundle и допускает один активный набор на точный catalog release. Публикации каталога и правил используют общий advisory lock; сбой сохраняет прежний активный набор.

Проверка BE-11: `mvnw.cmd verify` — PASS, 24 unit/API/config + 52 PostgreSQL IT, без пропусков. Включены 6 проверок схемы/неизменяемости и 10 сценариев импорта: strict JSON, ссылки и источники, полнота назначений, конфликт норм/версии, повтор старого набора, rollback после записи и конкурентные публикации.

## Точная арифметика — BE-12

Независимое Java-ядро вычисляет геометрию, пользовательский запас и упаковки на BigDecimal. Промежуточные значения не округляются; число упаковок округляется вверх непосредственно при делении. Цена должна быть положительной и выражаться целыми копейками; деньги сериализуются с двумя знаками. Известные единицы не конвертируются неявно. Количество упаковок ограничено безопасным целым JavaScript (9007199254740991).

`mvnw.cmd verify` — PASS: 38 unit/API/config + 52 PostgreSQL IT. Включены независимые эталоны 22/1,44 → 16 упаковок и 34560,00 RUB; 0,3/0,1 → 3 упаковки, очень малая потребность без преждевременного округления, запас 0–30%, границы геометрии и безопасного целого.

## Стратегии материалов — BE-13

Поддержаны покрытия, краска, грунтовка, фиксированный учебный клей, обои, розетки и кабель. Норма явно назначенного профиля — источник расчёта; заполненная норма карточки должна ей соответствовать. Слои применяются только к краске, процентный запас не применяется к розеткам. Дробное число розеток отклоняется; дробная длина кабеля допустима.

Обои считают целые полотна/рулоны по ширине, длине и известному раппорту, с подрезкой 0,10 м и запасом до округления полотен; проёмы не вычитаются. Постоянная фасовка для обоев отсутствует (`null`). Клей поддерживает только учебные 4 кг/м² для условно ровного основания; толщина слоя и фактическая технология не моделируются. Источники, формулы и ограничения доступны в результате.

`mvnw.cmd verify` — PASS: 74 unit/API/config + 52 PostgreSQL IT. Проверены независимые эталоны всех стратегий, раппорт 0/>0, слишком длинное полотно, неизвестные/противоречивые данные, дробные точки и 13 знаков потребности без промежуточного округления.

## API калькулятора и готовность — BE-14

После публикации каталога и его правил запустите обычное приложение и выполните:

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8000/api/v1/product/ac1f222a-f5e9-3b5d-a91e-c943b053e92e/calc -ContentType 'application/json' -Body '{"area":20,"perimeter":18,"height":2.7,"reserve":10,"layers":2,"quantity":6}'
```

Результат содержит ровно одну строку (`roomId=product`, `roomName=Расчёт товара`), `mode=api`, UUID/UTC-время, версии catalog/rules, `model=not-used` и `labor=0.00`. Для указанной плитки: need=22, quantity=16, purchased=23.04, remainder=1.04, materials=total=`34560.00`. Цена и фасовка берутся с сервера. Все шесть полей обязательны; layers и число розеток должны быть целыми, дробные метры кабеля допустимы.

Повреждённый JSON даёт 400, неизвестный товар — 404, неверный ввод/неполные данные — 422. Неполный лак остаётся заблокирован. Каталог, правила или БД недоступны — безопасный 503. Одна операция фиксирует неизменяемые snapshots каталога и совместимых правил; смена публикации между запросами к БД не смешивает версии. Новый каталог без своих правил сохраняет работу витрины и возвращает 503 для расчётов/readiness; после импорта совместимых правил readiness становится 200 без перезапуска. Liveness независим.

Итоговая `scripts/verify-contract.ps1` — PASS: **80 unit/API/config + 60 PostgreSQL IT, 0 skipped**, Node/productSchema для 12 товаров и Node/estimateSchema для 11 расчётов. Восемь новых HTTP IT проверяют числа, ошибки, точность дробной потребности, readiness, публикацию между запросами, сохранность прежнего snapshot и остановку принадлежащей тесту БД. Foundation-тесты явно заменяют только бизнес-индикатор, продолжая проверять настоящую БД; новые API IT используют полный production-контекст.

Дополнительная проверка собранного JAR через Docker CLI — PASS: публикации каталога/правил exit 0, повтор правил exit 0/no-op, отсутствующий файл — ненулевой exit. CLI не запускает HTTP. Тот же работающий сервер после публикации правил меняет readiness с 503 на 200; его 11 ответов приняты frontend Zod, неполная карточка даёт 422. Повтор и ошибка сохраняют единственный rule_set. Временные процесс и контейнер остановлены.

## Выполненные задачи

| Задачи | Результат | Проверки |
| --- | --- | --- |
| BE-02, BE-04 | Java 21, Boot 4.1.2, Maven Wrapper 3.10.0, Enforcer | `mvnw.cmd verify`: PASS (1 тест); `validate` на Java 17: ожидаемый отказ Enforcer |
| BE-05 | Local/test/deploy config, liveness/readiness, ограниченный health | `mvnw.cmd -Dtest=RepairCalcApplicationTest,HealthEndpointsTest verify`: PASS (4 теста, включая реальный HTTP) |
| BE-06 | ApiError, DTO/method validation, безопасные ошибки и UUID/MDC | `mvnw.cmd verify`: PASS (17 тестов, включая 13 MVC/filter tests и проверку request ID в stdout) |
| BE-07 | PostgreSQL/Flyway/JPA, schema V1, обязательный Testcontainers pipeline | `mvnw.cmd verify`: PASS (19 unit/API/config + 5 PostgreSQL IT, 0 skipped) |
| BE-08 | V2: версии каталога, стабильные UUID, исходные JSONB, ревизии и защита публикации | `mvnw.cmd verify`: PASS (19 unit/API/config + 11 PostgreSQL IT, включая 6 catalog persistence tests) |
| BE-09, схема назначений | V3: work-taxonomy-v1 и несколько проверяемых назначений товара | `mvnw.cmd verify`: PASS (19 unit/API/config + 20 PostgreSQL IT, включая 9 work mapping tests) |
| BE-09, импорт | Строгий JSON, консольный импорт 12 карточек, атомарная публикация и повтор | `mvnw.cmd verify`: PASS (19 unit/API/config + 30 PostgreSQL IT, включая 10 import tests) |
| BE-10 | Опубликованный snapshot, GET /api/v1/products, безопасный 503, frontend-контракт | `scripts/verify-contract.ps1`: PASS — 24 unit/API/config + 36 PostgreSQL IT (0 skipped), реальный HTTP-ответ с 12 товарами принят существующим `productSchema` через Node |
| BE-11 | V4, versioned typed RuleSet, источники/тарифы, строгий CLI loader и атомарная публикация | `mvnw.cmd verify`: PASS (24 unit/API/config + 52 PostgreSQL IT, 0 skipped) |
| BE-12 | Чистые Geometry/Money/Unit, запас и точное округление упаковок | `mvnw.cmd verify`: PASS (38 unit/API/config + 52 PostgreSQL IT, 0 skipped) |
| BE-13 | Семь стратегий, формулы/источники и явные учебные ограничения | `mvnw.cmd verify`: PASS (74 unit/API/config + 52 PostgreSQL IT, 0 skipped) |
| BE-14 | ProductCalc API, согласованный snapshot, readiness и frontend-контракты | `scripts/verify-contract.ps1`: PASS (80 unit/API/config + 60 PostgreSQL IT, 0 skipped; 12 товаров + 11 расчётов приняты Zod) |

Решения по стеку: [ADR-001](docs/adr/001-backend-foundation.md). Инструкция передачи и запуска: [TEAM_SETUP](docs/TEAM_SETUP.md).
