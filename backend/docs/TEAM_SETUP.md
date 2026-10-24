# Запуск Java backend на компьютере участника команды

Рабочая схема: **Java на компьютере + PostgreSQL в Docker**. Backend Dockerfile и общая Compose-интеграция ещё не добавлены; существующий `make up` запускает frontend.

Сейчас доступны каталог из 12 учебных карточек, калькулятор 11 карточек и health probes. Неполный лак отклоняется с 422. Подбор `/recommendations`, общая смета `/estimate`, полный JSON/XML-импорт и ML относятся к следующим этапам. Цены и нормы тестовые.

## Что установить и получить

- Git либо архив исходников ветки `backend`, содержащий BE-14 (коммит `ca48458` или новее). Локальные коммиты одного участника автоматически не появляются у остальных: получите опубликованную ветку или переданный архив.
- **JDK 21**: нужны `java` и `javac`; `JAVA_HOME` должен указывать на этот JDK, а не на JRE/JDK 17.
- Работающий Docker с **Linux containers**: Docker Desktop на Windows/macOS либо Docker Engine на Linux. `docker version` должен показывать Client и Server. На Windows сначала завершите настройку/перезагрузку WSL2, если этого требует Docker Desktop.
- Интернет для первой загрузки Maven, Java-зависимостей и образа `postgres:17.11`.

Глобальный Maven, GNU Make, Python и Node для самого backend не нужны. Maven Wrapper входит в исходники. Node/npm нужны только для дополнительных проверок frontend-контрактов ниже.

Docker Desktop с установленным CLI можно запустить командой `docker desktop start --detach`. После запуска дождитесь, чтобы `docker version` показывал версию Server.

После распаковки/получения ветки найдите рядом `backend`, `frontend` и `docs`. Быстрый Windows-запуск ниже выполняется **из корня репозитория**; ручные команды — **из `backend`**. У каждого участника свои локальная БД и пароль.

## Windows — PowerShell

### Быстрый локальный запуск

Укажите установленный JDK 21 в `JAVA_HOME` и запустите Docker с Linux engine. Launcher использует `java.exe` из `JAVA_HOME`: команда `java` в `PATH` не требуется. Docker CLI должен быть доступен.

Первый запуск, **из корня репозитория**:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\backend\run-local.cmd -InitializeData
```

Повторный запуск из корня:

```powershell
.\backend\run-local.cmd
```

Если открыт новый терминал и `JAVA_HOME` не задан постоянно, снова задайте его перед командой.

`run-local.cmd` вызывает `backend/scripts/run-local.ps1`. Скрипт создаёт игнорируемый Git файл `backend/.env.local` с локальным случайным паролем и использует его при следующих запусках. PostgreSQL работает в контейнере `repaircalc-postgres`, данные сохраняются в named volume `repaircalc-pgdata`, адрес БД — `127.0.0.1:55432`.

Launcher собирает JAR через Maven Wrapper командой `package -DskipTests`, затем запускает сервер в текущем терминале на `http://127.0.0.1:8000`. Это быстрая сборка без тестов; полная проверка описана ниже. `Ctrl+C` останавливает Java-сервер; данные в volume сохраняются.

Только явный `-InitializeData` перед HTTP-стартом импортирует учебный каталог и правила. Обычный запуск данные не импортирует. На новой пустой БД без инициализации витрина и readiness возвращают 503; после инициализации ожидаются результаты из раздела HTTP-проверок ниже.

Для подготовки без HTTP-сервера используйте `.\backend\run-local.cmd -InitializeData -PrepareOnly`. Существующие контейнер и volume не сбрасываются. Если БД уже создана вручную, а `.env.local` отсутствует, launcher остановится: скопируйте `.env.example` в `.env.local` и задайте **прежний** пароль. Порт сервера можно поменять через `SERVER_PORT` в этом файле; занятый порт вызывает понятную ошибку.

### IntelliJ IDEA

Подключите `backend/pom.xml` как Maven project. В **Project SDK** выберите JDK 21, а в настройках **Maven Runner → JRE** — тот же JDK 21. JDK 17 не подходит для этого backend; настройки SDK корневого проекта могут отличаться от `JAVA_HOME` терминала.

Для запуска через терминал IDEA используйте launcher выше. При прямом запуске `RepairCalcApplication` передайте его процессу DB-переменные из локальной конфигурации: сам Spring Boot `.env.local` не читает. Пользовательские файлы `.idea` менять или коммитить для launcher не требуется.

### Ручной запуск — альтернатива launcher

Следующие шаги позволяют настроить всё вручную. Если PostgreSQL уже создан launcher, используйте пароль из `backend/.env.local`: новая переменная не меняет пароль существующей БД.

#### 1. JDK, Docker и окружение

Замените путь JDK и пароль своими значениями. Переменные действуют в текущем терминале; `.env.example` и `.env` Spring Boot автоматически не читает.

```powershell
cd backend
$env:JAVA_HOME = 'C:\path\to\jdk-21'
& "$env:JAVA_HOME\bin\java.exe" -version
& "$env:JAVA_HOME\bin\javac.exe" -version
docker version

$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55432/repaircalc'
$env:DB_USER = 'repaircalc'
$env:DB_PASSWORD = 'replace-with-your-local-password'
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8000'
$env:DOCKER_HOST = (docker context inspect --format '{{.Endpoints.docker.Host}}').Trim()
```

#### 2. PostgreSQL и проверка сборки

Следующая команда создаёт БД **первый раз**. Если контейнер уже существует, используйте `docker start repaircalc-postgres` вместо `docker run`.

```powershell
$env:POSTGRES_PASSWORD = $env:DB_PASSWORD
docker run --detach --name repaircalc-postgres --publish 127.0.0.1:55432:5432 --env POSTGRES_DB=repaircalc --env POSTGRES_USER=repaircalc --env POSTGRES_PASSWORD --mount source=repaircalc-pgdata,target=/var/lib/postgresql/data postgres:17.11
docker exec repaircalc-postgres pg_isready -U repaircalc -d repaircalc
```

Дождитесь `accepting connections`: при необходимости повторите `pg_isready` через несколько секунд. Затем выполните все Java-проверки; Testcontainers создаёт собственные временные БД, не изменяя эту локальную БД.

```powershell
.\mvnw.cmd verify
if ($LASTEXITCODE -ne 0) { throw 'Build/tests failed; stop here.' }
```

#### 3. Каталог, правила, сервер

Порядок импорта обязателен: сначала каталог, затем соответствующие ему правила. Обе команды завершаются сами, HTTP-сервер в них не запускается.

```powershell
& "$env:JAVA_HOME\bin\java.exe" -jar target/repaircalc-backend-0.1.0-SNAPSHOT.jar --repaircalc.command=import-catalog --repaircalc.catalog.file=data/catalog-v1.json --repaircalc.catalog.mappings=data/work-mappings-v1.json
if ($LASTEXITCODE -ne 0) { throw 'Catalog import failed.' }

& "$env:JAVA_HOME\bin\java.exe" -jar target/repaircalc-backend-0.1.0-SNAPSHOT.jar --repaircalc.command=import-rules --repaircalc.rules.file=data/rules-v1.json
if ($LASTEXITCODE -ne 0) { throw 'Rules import failed.' }

& "$env:JAVA_HOME\bin\java.exe" -jar target/repaircalc-backend-0.1.0-SNAPSHOT.jar
```

Последняя команда занимает терминал. Оставьте его открытым; остановка сервера — `Ctrl+C`.

### Проверка из второго терминала

Для HTTP-проверок DB-переменные не нужны.

```powershell
Invoke-RestMethod http://127.0.0.1:8000/actuator/health/liveness
Invoke-RestMethod http://127.0.0.1:8000/actuator/health/readiness
$taskProducts = Invoke-RestMethod http://127.0.0.1:8000/api/v1/products
$taskProducts.Count
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8000/api/v1/product/ac1f222a-f5e9-3b5d-a91e-c943b053e92e/calc -ContentType 'application/json' -Body '{"area":20,"perimeter":18,"height":2.7,"reserve":10,"layers":2,"quantity":6}'
```

Ожидания: оба health — `UP`/HTTP 200, товаров **12**, расчёт плитки — **16 упаковок**, `need=22`, `purchased=23.04`, `remainder=1.04`, `materials=total="34560.00"`, `labor="0.00"`, `mode="api"`.

## Linux/macOS — Bash/Zsh

### 1. Окружение

Укажите фактический путь установленного JDK 21. На macOS его можно получить командой `/usr/libexec/java_home -v 21`.

```bash
cd backend
export JAVA_HOME="/path/to/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
javac -version
docker version

export DB_URL='jdbc:postgresql://127.0.0.1:55432/repaircalc'
export DB_USER='repaircalc'
export DB_PASSWORD='replace-with-your-local-password'
export SPRING_PROFILES_ACTIVE='local'
export SERVER_ADDRESS='127.0.0.1'
export SERVER_PORT='8000'
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
```

### 2. БД, сборка и запуск

Контейнер создаётся один раз; для существующего выполните `docker start repaircalc-postgres`.

```bash
export POSTGRES_PASSWORD="$DB_PASSWORD"
docker run --detach --name repaircalc-postgres --publish 127.0.0.1:55432:5432 --env POSTGRES_DB=repaircalc --env POSTGRES_USER=repaircalc --env POSTGRES_PASSWORD --mount source=repaircalc-pgdata,target=/var/lib/postgresql/data postgres:17.11
docker exec repaircalc-postgres pg_isready -U repaircalc -d repaircalc
```

После `accepting connections` выполняйте следующие команды по очереди, продолжая только после успешного завершения предыдущей:

```bash
./mvnw verify
java -jar target/repaircalc-backend-0.1.0-SNAPSHOT.jar --repaircalc.command=import-catalog --repaircalc.catalog.file=data/catalog-v1.json --repaircalc.catalog.mappings=data/work-mappings-v1.json
java -jar target/repaircalc-backend-0.1.0-SNAPSHOT.jar --repaircalc.command=import-rules --repaircalc.rules.file=data/rules-v1.json
java -jar target/repaircalc-backend-0.1.0-SNAPSHOT.jar
```

Из Git `mvnw` приходит с правом выполнения. При распаковке ZIP это право может потеряться: тогда используйте `sh ./mvnw verify`. Сервер оставьте в первом терминале; во втором:

```bash
curl --fail --show-error http://127.0.0.1:8000/actuator/health/liveness
curl --fail --show-error http://127.0.0.1:8000/actuator/health/readiness
curl --fail --show-error http://127.0.0.1:8000/api/v1/products
curl --fail --show-error -H 'Content-Type: application/json' --data '{"area":20,"perimeter":18,"height":2.7,"reserve":10,"layers":2,"quantity":6}' http://127.0.0.1:8000/api/v1/product/ac1f222a-f5e9-3b5d-a91e-c943b053e92e/calc
```

Ожидаемые результаты те же: health 200/UP, 12 карточек и плитка 16 упаковок/34560.00 RUB.

## Повторный запуск и сохранность данных

На Windows при использовании launcher достаточно снова задать `JAVA_HOME`, если требуется, и выполнить из корня `.\backend\run-local.cmd` без `-InitializeData`.

При ручном запуске в новом терминале снова задайте **те же** DB-переменные и настройки JDK из своего раздела. Из `backend` запустите БД и дождитесь готовности:

```text
docker start repaircalc-postgres
docker exec repaircalc-postgres pg_isready -U repaircalc -d repaircalc
```

Затем запустите Java-сервер на своей платформе:

```powershell
# Windows / PowerShell
& "$env:JAVA_HOME\bin\java.exe" -jar target/repaircalc-backend-0.1.0-SNAPSHOT.jar
```

```bash
# Linux/macOS
"$JAVA_HOME/bin/java" -jar target/repaircalc-backend-0.1.0-SNAPSHOT.jar
```

При сохранённом `repaircalc-pgdata` повторный импорт не нужен: миграции не применяются повторно, каталог/правила и их UUID/версии сохраняются. Повтор импорта тех же файлов допустим: exit 0, сообщения `Catalog unchanged` / `Rules unchanged`, без дубликатов и повторной активации старой версии.

Остановка БД: `docker stop repaircalc-postgres`. Удаление контейнера без удаления named volume сохраняет БД. Не удаляйте `repaircalc-pgdata`, если данные нужны. Изменение `POSTGRES_PASSWORD` при повторном запуске **не меняет пароль существующей БД**; используйте пароль, с которым этот volume был создан.

Не переформатируйте поставляемые JSON-файлы: fingerprint учитывает точные байты обоих файлов каталога. Изменённый каталог требует новой версии и совместимого нового набора правил.

## Дополнительная проверка frontend-контрактов

Нужны Node **22.12+ ветки 22 либо 24 LTS** и npm, согласно frontend `package.json`. Из корня репозитория выполните `npm ci --prefix frontend`, затем перейдите в `backend`.

Windows/PowerShell:

```powershell
.\scripts\verify-contract.ps1
```

Linux/macOS без установки PowerShell:

```bash
./mvnw clean verify &&
node --experimental-strip-types src/test/js/catalog-contract.mjs target/contracts/products-response.json --fixtures &&
node --experimental-strip-types src/test/js/calculation-contract.mjs target/contracts/product-estimates-response.json
```

В PowerShell 7 на Linux/macOS также можно выполнить `pwsh -File scripts/verify-contract.ps1`. Java `verify` и PostgreSQL IT обязательны; неисправный Docker не считается успешным пропуском. Проверяются настоящие HTTP-ответы 12 карточек и 11 расчётов. Полный api-mode интерфейс пока не готов: мастер сметы и подбор требуют будущих `/estimate` и `/recommendations`.

## Проверенный результат передачи

На 24 октября 2026 года проверены Windows/JDK 21 и чистая копия исходников в Linux x86_64/Temurin 21:

- Windows: 140 Java-тестов (80 unit/API/config + 60 PostgreSQL IT), без ошибок и пропусков; реальные HTTP-ответы приняты frontend `productSchema` и `estimateSchema`.
- Linux: сборка Maven Wrapper и 80 unit/API/config tests; миграции V1–V4 на новой БД, консольные импорты, 12 карточек, 11 успешных расчётов и отказ неполной карточке с 422. Те же ответы проверены frontend-схемами.
- Повтор обоих импортов — no-op. После пересоздания PostgreSQL на том же named volume и перезапуска Java без импорта сохранились UUID/fingerprints/версии, readiness 200 и расчёт плитки 16 упаковок/34560.00 RUB.
- На настоящем Java 17 Maven Enforcer отклоняет сборку сообщением `RepairCalc requires JDK 21 or newer`.

Фактические прогоны на macOS и ARM не выполнялись; для них приведены команды установки окружения и запуска, без заявления о проверке на этих устройствах.

## Если запуск не удался

| Признак | Действие |
| --- | --- |
| Enforcer требует JDK 21 / UnsupportedClassVersionError | Проверьте `JAVA_HOME`; на Windows вызывайте `java.exe`/`javac.exe` из его `bin`. В IDEA проверьте Project SDK и Maven Runner JRE — JDK 21 |
| PowerShell не находит команду `java` | Используйте `run-local.cmd` или прямой вызов `& "$env:JAVA_HOME\bin\java.exe"`; добавлять Java в глобальный PATH для launcher не нужно |
| Docker показывает только Client / Testcontainers не находит daemon | Запустите Docker и Linux engine; проверьте `docker version` и endpoint текущего context, как в командах окружения |
| Имя контейнера уже занято | Запустите существующий `repaircalc-postgres`, не создавайте второй с тем же именем |
| Порт 55432 занят | Выберите свободный host-порт в `--publish` и тот же порт в `DB_URL` |
| Authentication failed | Используйте исходный пароль существующего volume; новая переменная `POSTGRES_PASSWORD` его не заменяет |
| Flyway/подключение не проходят при старте | Проверьте `pg_isready`, DB_URL/DB_USER/DB_PASSWORD в терминале Java и лог запуска |
| Liveness 200, readiness 503 | До импорта это ожидаемо; импортируйте каталог, затем правила, сверяя exit code. После нового каталога нужны правила именно его версии/fingerprint |
| Витрина работает, калькулятор возвращает 422 | Для неполного лака это ожидаемо; проверьте диапазоны полей, целочисленные layers/розетки и сообщение ApiError |
| Нельзя обратиться к localhost из другого контейнера | Здесь Java запускается на хосте. Для другого способа размещения DB_URL должен указывать на доступный Java адрес БД; Compose-интеграция ещё не реализована |

Подробности реализации и история проверок: [README backend](../README.md). Контракт: [OpenAPI](../../docs/openapi.yaml).
