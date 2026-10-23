# RepairCalc Java backend

Самостоятельный Spring MVC backend. Базовая версия Java — 21; preview features не используются.

## Сборка

Из каталога `backend`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
.\mvnw.cmd verify
```

Linux/macOS: `./mvnw verify`. Wrapper загружает фиксированную версию Maven; первая сборка требует доступа к Maven Central. Установленный глобально Maven не требуется.

## Конфигурация и health

По умолчанию включён профиль `local`, адрес `127.0.0.1:8000` соответствует существующему Vite proxy. Профиль `deploy` слушает `0.0.0.0:8000`. Адрес и порт переопределяются через `SERVER_ADDRESS` и `SERVER_PORT`; профиль — через `SPRING_PROFILES_ACTIVE`. Примеры находятся в `.env.example`: Spring Boot не читает `.env` автоматически, переменные нужно передать процессу.

- `GET /actuator/health/liveness`: HTTP 200, если процесс работает.
- `GET /actuator/health/readiness`: HTTP 503 до реализации публикации каталога и правил (BE-09/BE-11). Индикатор `calculationData` намеренно закрыт на этапе M1.
- В рабочей реализации BE-07 readiness также проверяет БД. Служебные env/config/beans endpoints отключены, детали health скрыты.

Профиль `test` находится только в test resources и использует случайный порт. Учётные данные и локальные `.env` не коммитятся.

## Ошибки API и request ID

Ответ ошибки соответствует `../docs/openapi.yaml`: `{"code":"...","message":"...","fields":["path"]}`. Пустой `fields` опускается. Некорректный JSON даёт 400, ошибки входной validation и `DomainException` — 422, неожиданные ошибки и нарушение контракта возвращаемого значения — безопасный 500. Стандартные статусы 404/405/415 сохраняются.

Каждый запрос получает новый серверный UUID в `X-Request-ID`; клиентский заголовок не используется как идентификатор. ID доступен в MDC и stdout-логах до завершения запроса, затем удаляется. Completion-лог содержит метод и HTTP-статус; URL, тело и параметры запроса не записываются.

## PostgreSQL и миграции — BE-07

Подготовлены JPA, PostgreSQL driver, Flyway и Testcontainers с версиями из Boot BOM. Миграция `V1__create_repaircalc_schema.sql` создаёт схему `repaircalc`; история Flyway находится в `public`. Hibernate использует `ddl-auto=validate`, `open-in-view` отключён. До появления сущностей в BE-08 проверка их таблиц не заявляется.

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

Решения по стеку: [ADR-001](docs/adr/001-backend-foundation.md). Общий backlog находится в `../docs/BACKEND_IMPLEMENTATION_PLAN.md`.
