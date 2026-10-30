# Запуск Java backend на компьютере участника команды

Для полного стека с Java внутри Docker используйте [Docker Compose](../../docs/deploy.md). Ниже описан альтернативный запуск для разработки: **Java на компьютере + PostgreSQL в Docker**.

## Общие настройки проекта (рекомендуемый запуск)

Прежний `backend/.env.example` перенесён в общие `env/.env.example` и
`env/secret.env.example`. Создайте локальные файлы через `make init-config`,
задайте пароль БД в `env/secret.env`; настройки профиля, JDBC URL, порта и
пользователя находятся в `env/.env`. Не оставляйте пароль/admin-хеш в публичном
файле. Полные правила: [CONFIG.md](../../docs/CONFIG.md).

Для этого варианта нужны Node.js 22.12+, GNU Make и JDK 21. Из корня:

```bash
make init-config
# Заполните env/secret.env (для старой БД сохраните прежний пароль).
make dependencies
```

Затем соберите JAR через Maven Wrapper (`backend/mvnw verify`, на Windows
`backend/mvnw.cmd verify`, выполняя из `backend`). Из корня:

```bash
make backend-import-catalog
make backend-import-rules
make backend
```

`scripts/local.mjs` передаёт оба файла в env Java-процесса. `make dependencies`
использует Compose-volume `repaircalc_pgdata`, а не старый `repaircalc-pgdata`.
Не смешивайте его со старым контейнером ниже на порту 55432: остановите один из
вариантов или осознанно выберите другой порт/DB_URL. Следующая инструкция —
самостоятельная альтернатива **без Node/Make**, с ручным экспортом переменных.

Сейчас доступны каталог из 12 учебных карточек, калькулятор 11 карточек и health probes. Неполный лак отклоняется с 422. Подбор `/recommendations`, общая смета `/estimate`, полный JSON/XML-импорт и ML относятся к следующим этапам. Цены и нормы тестовые.

## Что установить и получить

- Git либо архив исходников ветки `backend`, содержащий BE-14 (коммит `ca48458` или новее). Локальные коммиты одного участника автоматически не появляются у остальных: получите опубликованную ветку или переданный архив.
- **JDK 21**: нужны `java` и `javac`; `JAVA_HOME` должен указывать на этот JDK, а не на JRE/JDK 17.
- Работающий Docker с **Linux containers**: Docker Desktop на Windows/macOS либо Docker Engine на Linux. `docker version` должен показывать Client и Server. На Windows сначала завершите настройку/перезагрузку WSL2, если этого требует Docker Desktop.
- Интернет для первой загрузки Maven, Java-зависимостей и образа `postgres:17.11`.

Глобальный Maven, GNU Make, Python и Node для самого backend не нужны. Maven Wrapper входит в исходники. Node/npm нужны только для дополнительных проверок frontend-контрактов ниже.

После распаковки/получения ветки найдите рядом `backend`, `frontend` и `docs`. Все следующие команды запуска выполняются **из `backend`**. У каждого участника свои локальная БД и пароль.

## Windows — PowerShell

### 1. JDK, Docker и окружение

Замените путь JDK и пароль своими значениями. Переменные действуют в текущем терминале; `.env.example` и `.env` Spring Boot автоматически не читает.

```powershell
cd backend
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
javac -version
docker version

$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55432/repaircalc'
$env:DB_USER = 'repaircalc'
$env:DB_PASSWORD = 'replace-with-your-local-password'
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8000'
$env:DOCKER_HOST = (docker context inspect --format '{{.Endpoints.docker.Host}}').Trim()
```

### 2. PostgreSQL и проверка сборки

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

### 3. Каталог, правила, сервер

Порядок импорта обязателен: сначала каталог, затем соответствующие ему правила. Обе команды завершаются сами, HTTP-сервер в них не запускается.

```powershell
java -jar target/repaircalc-backend-0.1.0-SNAPSHOT.jar --repaircalc.command=import-catalog --repaircalc.catalog.file=data/catalog-v1.json --repaircalc.catalog.mappings=data/work-mappings-v1.json
if ($LASTEXITCODE -ne 0) { throw 'Catalog import failed.' }

java -jar target/repaircalc-backend-0.1.0-SNAPSHOT.jar --repaircalc.command=import-rules --repaircalc.rules.file=data/rules-v1.json
if ($LASTEXITCODE -ne 0) { throw 'Rules import failed.' }

java -jar target/repaircalc-backend-0.1.0-SNAPSHOT.jar
```

Последняя команда занимает терминал. Оставьте его открытым; остановка сервера — `Ctrl+C`.

### 4. Проверка из второго терминала

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

При новом терминале снова задайте **те же** DB-переменные и настройки JDK из своего раздела. Затем:

```text
docker start repaircalc-postgres
docker exec repaircalc-postgres pg_isready -U repaircalc -d repaircalc
java -jar target/repaircalc-backend-0.1.0-SNAPSHOT.jar
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

## Если запуск не удался

| Признак | Действие |
| --- | --- |
| Enforcer требует JDK 21 / UnsupportedClassVersionError | Проверьте `JAVA_HOME`, `java -version` и `javac -version`; они должны относиться к JDK 21 |
| Docker показывает только Client / Testcontainers не находит daemon | Запустите Docker и Linux engine; проверьте `docker version` и endpoint текущего context, как в командах окружения |
| Имя контейнера уже занято | Запустите существующий `repaircalc-postgres`, не создавайте второй с тем же именем |
| Порт 55432 занят | Выберите свободный host-порт в `--publish` и тот же порт в `DB_URL` |
| Authentication failed | Используйте исходный пароль существующего volume; новая переменная `POSTGRES_PASSWORD` его не заменяет |
| Flyway/подключение не проходят при старте | Проверьте `pg_isready`, DB_URL/DB_USER/DB_PASSWORD в терминале Java и лог запуска |
| Liveness 200, readiness 503 | До импорта это ожидаемо; импортируйте каталог, затем правила, сверяя exit code. После нового каталога нужны правила именно его версии/fingerprint |
| Витрина работает, калькулятор возвращает 422 | Для неполного лака это ожидаемо; проверьте диапазоны полей, целочисленные layers/розетки и сообщение ApiError |
| Нельзя обратиться к localhost из другого контейнера | Здесь Java запускается на хосте. Для другого способа размещения DB_URL должен указывать на доступный Java адрес БД; для полного Compose используется `postgres:5432` внутри сети проекта |

Подробности реализации и история проверок: [README backend](../README.md). Контракт: [OpenAPI](../../docs/openapi.yaml).
