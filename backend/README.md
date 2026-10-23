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

## Выполненные задачи

| Задачи | Результат | Проверки |
| --- | --- | --- |
| BE-02, BE-04 | Java 21, Boot 4.1.2, Maven Wrapper 3.10.0, Enforcer | `mvnw.cmd verify`: PASS (1 тест); `validate` на Java 17: ожидаемый отказ Enforcer |

Решения по стеку: [ADR-001](docs/adr/001-backend-foundation.md). Общий backlog находится в `../docs/BACKEND_IMPLEMENTATION_PLAN.md`.
