# ADR-001: Java backend foundation

Дата: 24 октября 2026. Статус: принято.

## Решение

- Один Maven module `ru.repaircalc:repaircalc-backend`, базовый пакет `ru.repaircalc`.
- Java 21, `maven.compiler.release=21`, Enforcer отклоняет JDK ниже 21. Сборка и runtime проверяются на JDK 21.
- Spring Boot parent/plugin 4.1.2; Spring MVC и Jakarta Validation. Версии Spring, Jackson и прочих библиотек управляются Boot BOM.
- Maven Wrapper 3.3.4 фиксирует Maven 3.10.0. Коммитятся оба скрипта и конфигурация wrapper; бинарный Maven distribution хранится вне Git.
- Хранилище: PostgreSQL 17.11, Spring Data JPA, Flyway, Testcontainers. JDBC driver, Flyway и test tools используют версии Boot BOM.
- Будущие Docker runtime и CI используют Java 21; Maven в CI запускается через тот же Wrapper. Полная Compose-интеграция и CI относятся к следующим этапам.
- Миграции владеют схемой; Hibernate только проверяет её. HTTP DTO отделены от persistence и расчётных объектов.
- Приложение группируется по функциональным областям. Пакеты создаются вместе с рабочим кодом, пустые заглушки модулей не нужны.

## Последствия

Python/ML не требуются для запуска. Предметные данные и расчёты появятся после технической основы. PostgreSQL-тесты используют отдельные контейнеры и не обращаются к другим установленным приложениям.

## Источники

- [Spring Boot 4.1.2 release](https://spring.io/blog/2026/10/23/spring-boot-4-1-2-available-now/)
- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Apache Maven distributions](https://maven.apache.org/download.cgi)
