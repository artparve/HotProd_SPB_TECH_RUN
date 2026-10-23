# RepairCalc Java backend

Самостоятельный Spring MVC backend. Базовая версия Java — 21; preview features не используются.

## Сборка

Из каталога `backend`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

Linux/macOS: `./mvnw verify`. Wrapper загружает фиксированную версию Maven; первая сборка требует доступа к Maven Central. Установленный глобально Maven не требуется.

## Конфигурация и health

По умолчанию включён профиль `local`, адрес `127.0.0.1:8000` соответствует существующему Vite proxy. Профиль `deploy` слушает `0.0.0.0:8000`. Адрес и порт переопределяются через `SERVER_ADDRESS` и `SERVER_PORT`; профиль — через `SPRING_PROFILES_ACTIVE`. Примеры находятся в `.env.example`: Spring Boot не читает `.env` автоматически, переменные нужно передать процессу.

- `GET /actuator/health/liveness`: HTTP 200, если процесс работает.
- `GET /actuator/health/readiness`: HTTP 503 до реализации публикации каталога и правил (BE-09/BE-11). Индикатор `calculationData` намеренно закрыт на этапе M1.
- Проверка БД добавляется в BE-07. Служебные env/config/beans endpoints отключены, детали health скрыты.

Профиль `test` находится только в test resources и использует случайный порт. Учётные данные и локальные `.env` не коммитятся.

## Ошибки API и request ID

Ответ ошибки соответствует `../docs/openapi.yaml`: `{"code":"...","message":"...","fields":["path"]}`. Пустой `fields` опускается. Некорректный JSON даёт 400, ошибки входной validation и `DomainException` — 422, неожиданные ошибки и нарушение контракта возвращаемого значения — безопасный 500. Стандартные статусы 404/405/415 сохраняются.

Каждый запрос получает новый серверный UUID в `X-Request-ID`; клиентский заголовок не используется как идентификатор. ID доступен в MDC и stdout-логах до завершения запроса, затем удаляется. Completion-лог содержит метод и HTTP-статус; URL, тело и параметры запроса не записываются.

## Выполненные задачи

| Задачи | Результат | Проверки |
| --- | --- | --- |
| BE-02, BE-04 | Java 21, Boot 4.1.2, Maven Wrapper 3.10.0, Enforcer | `mvnw.cmd verify`: PASS (1 тест); `validate` на Java 17: ожидаемый отказ Enforcer |
| BE-05 | Local/test/deploy config, liveness/readiness, ограниченный health | `mvnw.cmd -Dtest=RepairCalcApplicationTest,HealthEndpointsTest verify`: PASS (4 теста, включая реальный HTTP) |
| BE-06 | ApiError, DTO/method validation, безопасные ошибки и UUID/MDC | `mvnw.cmd verify`: PASS (17 тестов, включая 13 MVC/filter tests и проверку request ID в stdout) |

Решения по стеку: [ADR-001](docs/adr/001-backend-foundation.md). Общий backlog находится в `../docs/BACKEND_IMPLEMENTATION_PLAN.md`.
