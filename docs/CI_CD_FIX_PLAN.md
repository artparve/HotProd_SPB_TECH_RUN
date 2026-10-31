# План исправления CI/CD: MongoDB и ядро GitHub runner

Дата анализа: 31 октября 2026 года. Ветка: `backend`, HEAD `1c29c53`. Это план; изменения в код и workflow ещё не внесены.

## 1. Подтверждённая причина

Оба приложенных отчёта содержат одинаковый fatal MongoDB с ID `12257600`: процесс отказывается запускаться из-за проверки совместимости Linux kernel 6.19 и новее, со ссылкой на `SERVER-121912`.

Последовательность: `mongod` завершается с кодом 1 → Testcontainers не получает сообщение готовности → истекает ожидание → `MongoWorkflowIT` не запускается → Maven Failsafe завершает job ошибкой. Увеличение таймаута не устраняет завершение процесса.

Дополнительно проверены реальные запуски одного SHA:

- [Push CI 41141315084](https://github.com/artparve/HotProd_SPB_TECH_RUN/actions/runs/41141315084).
- [PR CI 41141320338](https://github.com/artparve/HotProd_SPB_TECH_RUN/actions/runs/41141320338).

В обоих прошли `frontend` и `python-and-deploy`; упал `backend`. В backend-логе 83 Java unit-теста прошли, ошибка относится к старту контейнера перед интеграционными тестами. `stack` и `images` пропущены из-за зависимостей. Лог push-job указывает runner image `ubuntu-24.04`, версия `20261025.353.1`. Точная версия ядра нашего runner в этих фрагментах не напечатана — её нужно добавить в диагностику.

Обновление Ubuntu runner — обоснованное объяснение отличия от локальных успешных прогонов. GitHub подтвердил аналогичный сбой на новых Ubuntu runner images и рекомендует временно использовать `ubuntu-22.04`. MongoDB отдельно отслеживает неверное определение upstream-версии ядра Ubuntu. Это не доказательство конкретного номера ядра нашего запуска; номер надо зафиксировать при следующей проверке.

## 2. Первый коммит: восстановить CI минимальным изменением

Файл: [.github/workflows/ci.yml](../.github/workflows/ci.yml).

1. Перевести **оба** задания, запускающие настоящий Mongo, на `ubuntu-22.04`: `backend` и `stack`.
2. Сохранить Java 21, replica set, транзакционные тесты, полный `mvn verify` и API-mode Playwright. Остальные jobs могут остаться на `ubuntu-24.04`.
3. Добавить перед запуском Mongo компактную диагностику: runner image/version, `uname -r`, upstream kernel signature при наличии, Docker daemon kernel/architecture, Docker/Compose версии и выбранный Mongo image/digest. Для удалённого Docker важен kernel демона, а не только kernel клиента.
4. Отправить новый SHA в `backend` и проверить PR/push jobs. Повторный запуск старого SHA использует старый workflow и не подтверждает исправление конфигурации.

Этот временный путь сохраняет MongoDB 8 и не требует изменения существующих данных. Образ job-контейнера Ubuntu не заменяет ядро Docker host: менять только `container.image` недостаточно.

## 3. Второй коммит: сделать Mongo-окружение воспроизводимым

Сейчас `mongo:8.0` жёстко задан в [MongoWorkflowIT.java](../backend/src/test/java/ru/repaircalc/storage/MongoWorkflowIT.java), а Compose независимо использует `MONGO_IMAGE_TAG=8.0`. Это плавающий tag и два независимых источника настройки.

1. Выбрать опубликованный проверенный patch-релиз MongoDB 8.0.x и зафиксировать tag/digest. Официальный Dockerfile на момент анализа содержит 8.0.34; доступность и digest выбранного образа нужно проверить при реализации.
2. Передавать согласованный image в Testcontainers, Compose, сквозной скрипт и CI. Переопределение оставить через env/property, убрать независимую строку `mongo:8.0` из теста.
3. Синхронизировать defaults в [env/.env.example](../env/.env.example), [docker-compose.yml](../docker/docker-compose.yml), [.github/workflows/cd.yml](../.github/workflows/cd.yml) и необходимые проверки конфигурации. Пользовательские локальные env автоматически не переписывать.
4. Добавить проверку согласованности выбранного образа между тестами и Compose.

Фиксация версии обеспечивает воспроизводимость, но сама по себе не исправляет несовместимое ядро. Переход обратно на Ubuntu 24.04 выполняется отдельным изменением после проверки опубликованного Mongo 8.0.x с необходимым upstream-исправлением. В актуальной карточке `SERVER-131779` перечислен 8.0.37; наличие записи Fix Version не означает наличие Docker image в registry.

## 4. Третий коммит: ранняя диагностика и защита деплоя

Затрагиваемые файлы: CI workflow, [verify-stack.py](../scripts/verify-stack.py), [deploy_target.py](../scripts/deploy_target.py), [deploy_diagnostics.py](../scripts/deploy_diagnostics.py), release-тесты и документация.

1. Перед дорогой сборкой/сквозным прогоном проверять совместимость выбранного Mongo image на фактическом Docker host. Проверка должна отличать exit процесса от обычной медленной готовности.
2. Проверить устойчивость запуска дольше 90 секунд с ping/CRUD и транзакцией replica set: однократный успешный ping недостаточен для выявления отложенного падения.
3. При сбое сохранять ограниченный Mongo log, exit code/OOM, kernel/runner сведения и image digest в артефакты. Добавить тип ошибки `mongodb_kernel_incompatible`, распознающий fatal `12257600` и соответствующий текст. Не выводить `Config.Env` и подставленные секреты Compose.
4. Для CD проверять совместимость **удалённого** Docker host до остановки/обновления рабочих сервисов. При необходимости использовать отдельный временный контейнер выбранного image без рабочих volumes и публичных портов. Проверка не должна запускать новую версию на существующем Mongo volume.
5. Сохранить текущий release gate: только успешный CI нужного SHA в `main`. Сохранить проверки реквизитов, владельца порта, backup и привязки volume. В приложенных отчётах нет подтверждения самостоятельной ошибки SSH/GHCR/Ansible; сначала устраняется CI-блокер.

Не выбирать исправлением пропуск интеграционных тестов, отключение replica set, массовые retry, возврат PostgreSQL, удаление volumes или смену Mongo major. Отключение allocator/kernel-проверок через env — отдельный технический вариант, требующий подтверждения безопасности и проверки на фактическом ядре; для первого исправления он не требуется.

## 5. Проверки и критерий готовности

1. Release/config-тесты проверяют согласованность Mongo image, выбор runner и классификацию инфраструктурного сбоя.
2. `mvn verify` на новом GitHub runner: 83 unit + **12 реально выполненных** Mongo integration tests, без `skipped` и замены Mongo моками.
3. `verify-stack.py --playwright` и `--base-only --no-build` проходят в GitHub: реальные Java/Mongo/Python, 3 основных browser-сценария, 4 отказа сервисов, seed, admin, preflight и backup/restore. Подменяется только транспорт модели.
4. Полный PR CI зелёный. Доверенный push CI также зелёный и публикует три immutable image одного SHA; успешность публикации проверяется отдельно от компиляции.
5. После объединения исправления в `main` — успешный CI для её SHA, затем отдельный операторский CD на тестовый стенд. Проверить готовность API, сохранение активной публикации и доступность backup. Production-размещение не подменять заявлением о локальном успехе.
6. Повторить инфраструктурную проверку на другом свежем runner, чтобы исключить случайное попадание на прежний образ VM. Обновить [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) фактическими remote run IDs и результатами.

## Источники

- [GitHub runner-images: подтверждение проблемы и временный runner 22.04](https://github.com/actions/runner-images/issues/15005#issuecomment-6294443549).
- [MongoDB production notes: ограничения версий Linux kernel](https://www.mongodb.com/docs/manual/administration/production-notes/).
- [SERVER-121912: rseq/TCMalloc](https://jira.mongodb.org/browse/SERVER-121912).
- [SERVER-131779: определение версии ядра Ubuntu и исправленные версии](https://jira.mongodb.org/browse/SERVER-131779).
- [Официальный Mongo 8.0 Dockerfile](https://github.com/docker-library/mongo/blob/master/8.0/Dockerfile).
