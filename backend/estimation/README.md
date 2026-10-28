# Расход и стоимость материалов: Ollama + tool калькулятор

Python 3.11+, только стандартная библиотека. Отдельный CLI и HTTP-сервис на `127.0.0.1:8010`.
Модель по умолчанию — `gemma4:12b`, как в классификаторе. Модель вызывает
`calculate_material_cost`, сервис обращается к существующему Java
`POST /api/v1/product/{id}/calc`, затем возвращает проверенную смету и объяснение модели.
Протокол вызовов — [официальный Ollama tool calling](https://docs.ollama.com/capabilities/tool-calling).
LangChain для этого небольшого ограниченного цикла не требуется.

## Запуск

Все команды из корня репозитория. Нужны запущенные Java backend с PostgreSQL,
опубликованным каталогом и правилами ([настройка](../docs/TEAM_SETUP.md)) и Ollama
с установленной моделью ([проверка](../docs/OLLAMA_SMOKE.md)). Сервис не скачивает веса
и не запускает другие процессы автоматически. Python-пакеты устанавливать не нужно.

```bash
export PYTHONPATH=backend/estimation
python3 -m repaircalc_estimator serve
```

В другом терминале:

```bash
curl http://127.0.0.1:8010/health
curl --fail-with-body http://127.0.0.1:8010/api/v1/materials/estimate \
  -H 'Content-Type: application/json' \
  --data-binary @backend/estimation/examples/paint.json
```

`GET /health` проверяет только жизнь Python-процесса; `dependenciesChecked=false`.
Готовность Java: `http://127.0.0.1:8000/actuator/health/readiness`.
Ollama проверяется существующим smoke-скриптом. Успех реального inference подтверждает `/estimate`.
Один запрос к модели выполняется за раз; параллельный получает `503 model_busy`.
Это локальный сервис без аутентификации; штатный адрес — loopback.

CLI без запуска HTTP-сервера:

```bash
PYTHONPATH=backend/estimation python3 -m repaircalc_estimator estimate \
  --input backend/estimation/examples/paint.json --output /tmp/paint-estimate.json

# Детерминированный режим: только Java, без Ollama.
PYTHONPATH=backend/estimation python3 -m repaircalc_estimator calculate \
  --input backend/estimation/examples/tile.json

# Общие параметры ставятся ПЕРЕД подкомандой.
PYTHONPATH=backend/estimation python3 -m repaircalc_estimator \
  --backend http://127.0.0.1:8000 --host http://127.0.0.1:11434 \
  --model gemma4:12b --timeout 600 estimate \
  --input backend/estimation/examples/wallpaper.json
```

PowerShell:

```powershell
$env:PYTHONPATH = 'backend/estimation'
py -3 -m repaircalc_estimator serve
# Или одиночный запрос:
py -3 -m repaircalc_estimator estimate --input backend/estimation/examples/paint.json
```

`--timeout` задаёт socket timeout Ollama, не общий дедлайн двух обращений к модели.
Java имеет отдельный timeout 30 секунд. CLI пишет результат в stdout или `--output`,
сообщение о начале и ошибки — в stderr. Exit 0 — результат, 1 — ошибка, 2 — ошибка аргументов,
130 — Ctrl+C. На CPU inference может занять несколько минут.

## Контракт входа

```json
{
  "productId": "30473701-b10b-3d56-a15a-8591c16486c4",
  "input": {"area": 20, "perimeter": 18, "height": 2.7, "reserve": 10, "layers": 2, "quantity": 6},
  "prompt": "Сколько краски купить и сколько это стоит?",
  "useCorrection": false
}
```

`productId` — UUID из `GET /api/v1/products`. JSON `id`, `cod_good` и XML offer ID из
сырого каталога не являются этим UUID. Результат классификации сам по себе не назначает
Java-профиль расчёта. Для исходных карточек нужен существующий процесс импорта, назначения
норм и правил; сервис не придумывает недостающие нормы из названия товара.

| Поле input | Единицы и ограничения существующего Java API |
| --- | --- |
| area | Площадь рассчитываемой поверхности, м², `0 < area <= 10000` |
| perimeter | Оклеиваемый периметр, м, `0 < perimeter <= 1000` |
| height | Высота, м, `1..10` |
| reserve | Пользовательский запас, %, `0..30` |
| layers | Целое число слоёв краски, `1..4` |
| quantity | Заданное количество точек/длина кабеля, `0 < quantity <= 5000` |

Все шесть полей обязательны, даже если конкретная формула использует часть из них:
таков контракт текущего Java API. Decimal-строки также допустимы. `area` для краски —
площадь окрашивания, а не автоматически площадь пола. `prompt` необязателен, до 8000 символов.
Он управляет объяснением; численные параметры берутся из `input`.

## Формулы и результат

Единственный источник формул, округления и денег —
[MaterialCalculator.java](../src/main/java/ru/repaircalc/calculation/domain/MaterialCalculator.java).
Новые копии формул в Python не создаются.

- Краска: `area × consumption × layers × (1 + reserve/100)`.
  `consumption` — л/м²/слой из назначенного профиля, а не м²/л. Если исходник содержит
  м²/л, преобразование и подтверждение нормы выполняются при подготовке профиля.
- Плитка и другие покрытия: `area × (1 + reserve/100)` м².
- Обои: длина полотна = высота + 0,10 м, с округлением вверх до раппорта;
  полотен в рулоне = `floor(rollLength / stripLength)`;
  требуемые полотна = `ceil(perimeter × (1 + reserve/100) / rollWidth)`.
  Проёмы не вычитаются, рулоны округляются вверх.
- Грунт, клей, розетки и кабель поддерживаются назначенными Java-профилями.
- Упаковки: `ceil(need / packageSize)`. Стоимость — целые упаковки × цена упаковки,
  денежная арифметика остаётся в Java `Money`/`BigDecimal`.

Возвращается envelope:

```json
{
  "status": "ok",
  "estimate": {"...": "исходный ProductEstimate из Java"},
  "baseline": {"...": "ProductEstimate до ML-поправки"},
  "correction": {"applied": false, "version": "not-used", "factor": "1"},
  "model": "gemma4:12b",
  "toolCalls": ["calculate_material_cost"],
  "explanation": "Справочное объяснение модели",
  "explanationStatus": "unverified_model_text"
}
```

Это иллюстрация envelope; полные реальные ответы находятся в
[отчёте](../../reports/estimation/2026-10-24/README.md).
`estimate.lines` содержит товар, количество упаковок `quantity`, расход `need`,
купленный объём `purchased`, остаток `remainder`, `total`, `formula`, `reason`.
`estimate.materials/labor/total` — денежные строки; `warnings` и `versions` Java сохраняются.
`estimate.versions.model="not-used"` верно для арифметики Java;
верхний `model` отдельно указывает модель помощника. Версия ML — в `correction.version`.

`POST /api/v1/materials/calculate` принимает тот же вход, возвращает тот же расчётный envelope
без объяснения и `toolCalls`, с `model="not-used"`.
Модель получает инструмент с пустыми аргументами: она не может подменить цену, товар,
норму или площадь. Сервис исполняет только один известный вызов. Ответ модели без вызова,
с другим инструментом, с произвольными аргументами или с обрезанной генерацией — HTTP 502.
Если объяснение не удалось после успешного расчёта, смета возвращается с
`explanationStatus="unavailable"` и `explanationError`. Текст модели не проверяется
на численную достоверность: интерфейс должен отображать суммы из `estimate`.

Ошибки Java (в том числе неизвестный товар, отсутствующая норма и неготовые данные)
сохраняют HTTP-статус и `{code, message, fields}`. Невалидный вход — 422,
невалидный JSON — 400, недоступная зависимость — 503. Молчаливого перехода на выдуманный
результат или baseline при запрошенной, но недоступной ML-поправке нет.

## Обучаемые поправки

Реализована параметрическая регрессия без свободного члена отдельно для каждого SKU:
`actualConsumed ≈ factor × baselineNeed`, `factor = Σ(x × y) / Σ(x²)`.
По умолчанию требуется минимум 5 независимых заказов на товар; дубли `(orderId, productId)`
запрещены. Это коэффициент расхода, а не прогноз цены.

Данные обучения — JSON-массив строк такого вида:

```json
{
  "orderId": "order-unique-id",
  "productId": "30473701-b10b-3d56-a15a-8591c16486c4",
  "kind": "paint",
  "unit": "л",
  "catalogVersion": "synthetic-catalog-v1",
  "rulesVersion": "synthetic-rules-v1",
  "baselineNeed": "5.28",
  "actualConsumed": "5.80"
}
```

`baselineNeed` берётся из `baseline.lines[0].need` для исходных параметров заказа,
включая исходный запас. `actualConsumed` — фактически израсходованный материал в той же
единице, с учётом отходов; не число купленных банок и не стоимость.
Подготовка и достоверность этих меток — ответственность источника заказов.
Данные разных единиц/версий для одного SKU не смешиваются.

```bash
# СИНТЕТИЧЕСКИЙ пример проверяет механику; не модель, обученная на реальных заказах.
PYTHONPATH=backend/estimation python3 -m repaircalc_estimator train \
  --input backend/estimation/examples/synthetic-orders.json \
  --output /tmp/repaircalc-coefficients.json

PYTHONPATH=backend/estimation python3 -m repaircalc_estimator \
  --correction /tmp/repaircalc-coefficients.json calculate \
  --input backend/estimation/examples/paint-corrected.json

# Для HTTP: тот же артефакт и useCorrection=true в запросе.
PYTHONPATH=backend/estimation python3 -m repaircalc_estimator \
  --correction /tmp/repaircalc-coefficients.json serve
```

Сервис рассчитывает baseline, проверяет совпадение SKU, единицы, каталога и правил,
затем переводит коэффициент в эффективный запас:
`effectiveReserve = (100 + originalReserve) × factor - 100`.
При исходном запасе 10% и `factor=1.1` это 21%, а не повторное прибавление 10%.
Повторный Java-запрос считает упаковки, остаток и стоимость по скорректированному расходу.
Обе сметы сохраняются. Если версии данных сменились между запросами — 409, нужен повтор.

Поддержаны непрерывные нормы: `paint`, `primer`, `covering`, `adhesive`, `cable`.
Обои используют дискретные полотна и геометрию: к ним эта регрессия не применяется.
Розетки также исключены. Коэффициент ограничен `0.5..1.5`, эффективный запас — Java-диапазоном
`0..30%`. Выход за диапазон требует уточнения, без скрытого ограничения значения.
Артефакт содержит версию, SHA-256 данных, число примеров и **training MAE**; эта метрика
на обучении не является оценкой качества на новых заказах. Перед применением на реальных
объектах нужны отдельные проверочные заказы и сравнение с baseline. Реального датасета
заказов в исследованных материалах нет, качество регрессии на них не заявляется.

## Проверки

```bash
PYTHONPATH=backend/estimation python3 -m unittest discover -s backend/estimation/tests -v
```

Тесты формул уже существуют в Java; Python проверяет контракт, tool calling, ошибки,
обучение и применение коэффициента, смену версий и HTTP-сервис. Ollama/Java для unit-тестов
не нужны; HTTP-тесты поднимают временный loopback-порт.
Реальный smoke и ожидаемые числа: [ESTIMATION_SMOKE.md](../docs/ESTIMATION_SMOKE.md).

## Подключение frontend

В `env/.env` установите `VITE_DATA_MODE=api`, адрес Java `API_UPSTREAM` /
`DOCKER_API_UPSTREAM` и адрес этого сервиса `ESTIMATION_UPSTREAM` /
`DOCKER_ESTIMATION_UPSTREAM`. Для Vite обычно это `http://127.0.0.1:8010`; nginx
в контейнере нужен доступный ему адрес, например `http://host.docker.internal:8010`
при привязке Python к Docker-интерфейсу хоста. После изменения пересоберите frontend.

В `/product` по умолчанию включено «Добавить пояснение ИИ». Frontend передаёт UUID
товара и шесть полей формы в `/api/v1/materials/estimate`, проверяет envelope и показывает
`estimate` отдельно от непроверенного `explanation`. Таймауты браузера и nginx увеличены
для двух обращений к модели; при ошибке tool calling прямой расчёт автоматически не
выполняется. Снятие флажка явно включает существующий Java endpoint без Ollama.
Java `versions.model=not-used` относится к арифметике, а верхний `model` — к помощнику.

Протокол сквозной браузерной проверки: [frontend → модель → tool → Java](../../reports/estimation/frontend-e2e-2026-10-24/README.md).
