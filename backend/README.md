# Java backend

Java 21 и Spring Boot предоставляют публичный API RepairCalc. MongoDB хранит каталог, immutable revisions, правила, административные версии, очередь и аудит. База должна быть replica set: публикация использует транзакции. Python вызывается по внутреннему HTTP и к БД не подключается.

## Локальная разработка

Из корня: `make init-config`, заполнить Mongo-секреты, `make dependencies`. Java запускается из каталога `backend`; стандартный Maven Wrapper работает как `./mvnw`, на Windows — `mvnw.cmd`.

```sh
cd backend
./mvnw package
cd ..
make backend-import-catalog
make backend-import-rules
make backend-import-inspirations
make backend
```

`make backend` использует jar и общий env-loader. Для IDE используйте Run Configuration `Backend Java`/`Backend local` и передайте те же env явно; старый автоматический import backend/.env.local удалён. Shared данные лежат в корневом `data/`.

## API

- GET `/api/v1/products` — каталог; при сбое БД пустой массив и X-RepairCalc-Degraded.
- POST `/api/v1/product/{id}/calc` — расчёт одного товара, без полного чтения каталога.
- POST `/api/v1/recommendations` — проверяемый подбор по условиям комнаты.
- POST `/api/v1/estimate` — общая смета с явными зависимостями и selections.
- `/api/v1/admin/*` — session/CSRF, версии, классификация, review, readiness, publication.
- `/api/v1/room-inspirations*` — подготовленная подборка, файлы и Python-анализ.
- `/api/v1/materials/*`, `/api/v1/plans/extract` — Java-фасад LLM.

Точный контракт — `docs/openapi.yaml` и `docs/admin-catalog-api.json`. Старые SQL-модели и миграции не участвуют в запуске.

## Проверки

`./mvnw test` — unit/web tests. `./mvnw verify` — дополнительно Mongo Testcontainers. Docker должен быть запущен. Идемпотентный seed, Decimal128, конкурентные изменения/публикации, rollback и lease fencing проверяются на настоящем replica set.

Все цены и нормы учебные. Неподтверждённый расход, обязательный материал или тариф приводят к 422; несовместимый выбор — 409; недоступная БД при расчёте/записи — 503.
