# Классификация товаров: LangChain + structured output

Для карточек админки добавлен PostgreSQL-worker: `python -m repaircalc_classifier.worker`. Очередь, аренды, повторные попытки, переменные окружения и проверка человеком описаны в [административной вертикали](../../docs/admin-workflow.md). CLI ниже остаётся самостоятельным инструментом.

Автономный Python-модуль для multi-label классификации JSON/XML-карточек через локальный Ollama. Существующее Java API не изменено. Модель возвращает виды работ, места применения, материалы основания и свойства; для каждого утверждения требуется цитата из карточки. Полные списки и обоснование по данным — в [отчёте](../../reports/classification/README.md).

## Файлы

- [taxonomy.json](repaircalc_classifier/taxonomy.json) — единый словарь: 23 работы, 8 мест применения, 17 оснований, 7 свойств. `candidate_pattern` нужен только для аудита кандидатов.
- [prompt.txt](repaircalc_classifier/prompt.txt) — русский системный промпт, правила неоднозначностей и примеры; в `{taxonomy}` код подставляет словарь.
- [schema.py](repaircalc_classifier/schema.py), [output.schema.json](repaircalc_classifier/output.schema.json) — строгая Pydantic-модель и её JSON Schema. Источник истины — schema.py + taxonomy.json, JSON Schema экспортирована для просмотра.
- [classifier.py](repaircalc_classifier/classifier.py) — `ChatPromptTemplate | ChatOllama.with_structured_output(Classification, method="json_schema", include_raw=True)` и проверка цитат.
- [catalogs.py](repaircalc_classifier/catalogs.py) — адаптеры исходных датасетов; HTML очищается, повторные атрибуты сохраняются, XML-иерархия и единицы параметров передаются модели.
- [audit.py](repaircalc_classifier/audit.py) — воспроизводимый полный проход по двум датасетам, без вызовов модели.

## Мониторинг и live-режим

[Результаты реальной проверки и автоматических тестов](../../reports/classification/MONITORING.md).

Проверить сервер и модель без запуска inference (из корня репозитория):

```bash
PYTHONPATH=backend/classification backend/classification/.venv/bin/python -m repaircalc_classifier --status
```

В JSON отдельно указаны `reachable` (Ollama отвечает), `installed` (нужная модель установлена) и `loaded` (модель сейчас в памяти). `loaded=false` нормально перед первым запуском: запрос загрузит установленную модель. Наличие в памяти не доказывает, что модель генерирует ответ. При недоступном `/api/ps` состояние `loaded=null`, подробности — в `runtime_error`; это не подменяет успешную проверку сервера через `/api/tags`. Exit 0 означает доступный сервер с установленной моделью, exit 1 — сервер недоступен или модель отсутствует. Модель автоматически не скачивается.

Обычный запуск теперь показывает ход запроса каждые 10 секунд. Чтобы также видеть ответ по мере генерации:

```bash
PYTHONPATH=backend/classification backend/classification/.venv/bin/python -m repaircalc_classifier \
  --input backend/classification/examples/paint.json \
  --model gemma4:12b --live --progress-interval 10 \
  --output /tmp/paint-live-new.jsonl
```

Временно установленное окружение текущей машины также подходит: замените `backend/classification/.venv/bin/python` на `/tmp/repaircalc-classifier-venv/bin/python`.

Что видно в терминале:

1. Доступен ли Ollama, установлена ли модель, есть ли она в памяти.
2. Ожидание первого фрагмента, время с начала запроса. До первого фрагмента API не позволяет точно разделить очередь, загрузку весов и обработку входа.
3. Момент первого фрагмента и поступающий черновой JSON при `--live`.
4. Периодические сообщения: модель в памяти, число фрагментов и символов, время без новых фрагментов. В live-режиме во время нормального поступления текста heartbeat не вклинивается между фрагментами JSON; при паузе сообщения возобновляются. Фрагмент потока не равен токену.
5. Проверка завершения ответа, Pydantic-схемы и цитат.
6. `ГОТОВО`, `ТРЕБУЕТ ПРОВЕРКИ`, `ОШИБКА` или `ПРЕРВАНО`.

Прогресс и live-поток идут **в stderr**, итоговый проверенный JSONL — в stdout или `--output`. Черновой поток может быть неполным/ошибочным; результатом считается только JSON после успешной проверки. При ошибке частичный ответ не выдаётся за готовую классификацию. Ctrl+C возвращает exit 130; ошибка ответа — exit 1. Смысловые конфликты дают `monitoring.status="review"`, при этом `status="ok"` означает корректную структуру/цитаты, а `classification.needs_review=true` указывает необходимость ручной проверки.

В итоговой строке добавлено поле `monitoring`: статус, полная длительность, задержка первого фрагмента, число фрагментов/символов и количество выходных токенов из метаданных API. `--quiet` отключает сообщения о ходе запроса, но сохраняет итоговый JSON и телеметрию; с `--live` его сочетать нельзя. `--health-timeout` (по умолчанию 3 секунды) ограничивает отдельную проверку состояния, `--timeout` по-прежнему задаёт socket timeout inference. Это не общий лимит длительности запроса. Процент готовности и ETA не выводятся: API не сообщает достоверного прогресса обработки промпта и оставшегося объёма генерации.

## Установка и первый запрос

Команды ниже выполняются **из корня репозитория**. Нужен Python 3.11+ с venv/pip и доступный Ollama. Проверено на Python 3.14 с версиями из requirements.txt. Установка зависимостей и загрузка модели требуют сети; первая загрузка модели может быть большой. Если Ollama ещё не запущен, в отдельном терминале выполните `ollama serve`.

```bash
python3 -m venv backend/classification/.venv
backend/classification/.venv/bin/python -m pip install -r backend/classification/requirements.txt
# Тег из существующего smoke-скрипта проекта; можно указать другую установленную модель.
ollama pull gemma4:12b
PYTHONPATH=backend/classification backend/classification/.venv/bin/python -m repaircalc_classifier \
  --input backend/classification/examples/paint.json \
  --model gemma4:12b \
  --output /tmp/paint-classification.jsonl
```

Windows PowerShell (из корня репозитория):

```powershell
py -m venv backend/classification/.venv
& backend/classification/.venv/Scripts/python.exe -m pip install -r backend/classification/requirements.txt
ollama pull gemma4:12b
$env:PYTHONPATH = 'backend/classification'
& backend/classification/.venv/Scripts/python.exe -m repaircalc_classifier --input backend/classification/examples/paint.json --model gemma4:12b --output paint-classification.jsonl
```

`--model` — тег модели на указанном сервере. `--host` по умолчанию `http://127.0.0.1:11434`; CLI не загружает модель сам. Это позволяет использовать уже установленную модель, поддерживающую Ollama JSON Schema. При первоначальной проверке Ollama отвечал с `models=[]`. После загрузки модели пользователем выполнены реальные диагностические прогоны Gemma; [результаты и ограничения](../../reports/classification/live-2026-10-24/README.md). Модуль не загружает веса автоматически.

## Исходные каталоги и несколько товаров

```bash
# Один товар по JSON id (не cod_good):
PYTHONPATH=backend/classification backend/classification/.venv/bin/python -m repaircalc_classifier \
  --json-catalog products_final.json --source-id 123 \
  --output /tmp/product-123.jsonl

# Первые 10 XML-офферов с полным путём категории:
PYTHONPATH=backend/classification backend/classification/.venv/bin/python -m repaircalc_classifier \
  --xml-catalog export_yandex_spb.xml --limit 10 \
  --output /tmp/xml-classification.jsonl

# Подготовленная выборка из 12 реальных карточек:
PYTHONPATH=backend/classification backend/classification/.venv/bin/python -m repaircalc_classifier \
  --input reports/classification/sample_products.json --limit 12 \
  --output /tmp/sample-classification.jsonl
```

По умолчанию `--limit 1`: полный каталог самопроизвольно не отправляется в LLM. Вывод — JSONL, один результат на строку. Существующий файл не перезаписывается; для повторного запуска укажите новое имя. Ошибка конкретной карточки записывается со `status=error`, обработка остальных продолжается, exit code будет 1. Не найденный `--source-id` также даёт exit 1. Обрыв ответа, неправильная схема и выдуманные цитаты считаются ошибкой, а не пустой классификацией. Автоматических повторных запросов нет: пользователь контролирует повтор и расходы.

JSON-каталог читается полностью в память; XML обрабатывается потоково. Адаптеры не объединяют выгрузки и не выбирают одну карточку из повторяющихся cod_good. Для нормализованного входа передавайте объект или массив с `source`, `source_id`, `name`, опционально `cod_good`, `description`, `marketing_description`, `categories: [{id,name}]`, `attributes: [{name,value,unit?}]`. Свободные входные атрибуты сохраняйте списком. Произвольные дополнительные поля схема отклоняет; исходную карточку products_final.json сначала пропустите через `--json-catalog`.

## Использование из кода

При `PYTHONPATH=backend/classification` и установленных зависимостях:

```python
from repaircalc_classifier.classifier import build_chain, classify

model = "gemma4:12b"
host = "http://127.0.0.1:11434"
chain = build_chain(model=model, base_url=host)
result = classify(
    {
        "source": "my-catalog",
        "source_id": "42",
        "name": "Краска для стен и потолков",
        "attributes": [{"name": "Влагостойкость", "value": "нет"}],
    },
    chain,
    model=model,
    base_url=host,
)
print(result["classification"])
```

Смысл ответа для учебного `examples/paint.json` (это ожидаемое правило, **не сохранённый ответ модели**):

```text
scope=in_scope
work_types=[paint]
surface_locations=[wall, ceiling]
substrates=[concrete]
properties.moisture_resistant.state=no
properties.interior_suitable.state=yes
properties.wet_room_suitable.state=no
остальные свойства: unknown
needs_review=false
```

В реальном JSON каждая метка имеет `label` и `evidence: [{field,quote}]`; свойства содержат `state` и `evidence`. Неизвестное свойство имеет пустой evidence. Конфликт — минимум две цитаты и обязательный `needs_review=true`. Списки меток могут быть пустыми, но `in_scope` требует хотя бы один вид работ. В envelope сохраняются source/source_id/cod_good, версии и SHA-256 промпта, таксономии и входа, модель, host и response_metadata. Запрошенные model/host дублируют параметры цепочки: при вызове из кода передавайте одинаковые значения. В метаданных Ollama может вернуть фактическое имя модели; веса модели этим не фиксируются — для воспроизводимости отдельно сохраняйте её digest из Ollama.

`--timeout` — socket timeout, по умолчанию 600 секунд; `--num-ctx 16384`, `--num-predict 4096`. При переполнении контекста увеличьте параметры в пределах ресурсов сервера. Для слишком длинных карточек ограничения контекста могут повлиять на качество; CLI не обрезает поля молча. `reasoning=False` передаёт Ollama `think=false`, как в smoke-скрипте. `temperature=0` снижает вариативность, но не гарантирует побитовую воспроизводимость.

## Проверки и ограничения

```bash
PYTHONPATH=backend/classification backend/classification/.venv/bin/python \
  -m unittest discover -s backend/classification/tests -v
```

24 теста проходят. Проверены мониторинг, live-поток, отделение stdout/stderr, статусы сервера/модели, ошибки и прерывания; также адаптеры, сохранение повторов/единиц, недопустимые метки, выдуманные цитаты, конфликты, unknown/out_of_scope и полный путь LangChain → нативная схема Ollama → Pydantic → проверка evidence с имитацией транспорта API. Это не тест точности LLM. Входные инструкции внутри карточек отмечены в системном промпте как недоверенные данные; одного промпта недостаточно для гарантии защиты от любых инъекций.

JSON Schema гарантирует структуру, а проверка цитат — их наличие. Код не может доказать, что цитата логически обосновывает метку. Перед массовым импортом требуется независимая ручная multi-label выборка и micro/macro-F1 по осям; отчёт описывает разбиение и случаи для проверки. Не подменяйте такой тест частотами ключевых слов или учебными примерами из промпта.

Новая таксономия не импортируется в опубликованный Java `work-taxonomy-v1`: там только четыре вида работ и более узкие определения. Нет новых endpoint, расчётов норм, интеграции с Java/БД и изменения текущих API. Для этого потребуется отдельный адаптер и версия правил/контрактов.

Основание реализации: [официальный API ChatOllama.with_structured_output](https://reference.langchain.com/python/langchain-ollama/chat_models/ChatOllama/with_structured_output), [Ollama structured outputs](https://registry.ollama.com/blog/structured-outputs). `method="json_schema"` использует нативный параметр `format`, Pydantic валидирует разобранный ответ, `include_raw=True` сохраняет сведения об ошибке парсинга и метаданные.

## Реальные диагностические прогоны

Семь карточек с заранее заданными проверками: отрицание влагостойкости, IP44 без выдуманного основания, конфликт повторных атрибутов, универсальный грунт, реальная плитка, щепа для копчения и реальная краска. Это небольшая диагностическая выборка, а не независимый benchmark.

```bash
PYTHONPATH=backend/classification backend/classification/.venv/bin/python -m repaircalc_classifier.live_test \
  --cases backend/classification/examples/live_cases.json \
  --output /tmp/repaircalc-live-new-run
```

Требуется загруженная модель в Ollama. Выбирайте новую выходную папку: results.jsonl не перезаписывается. Метаданные содержат digest модели и параметры запуска; каждая строка результата — полный ответ, проверки и длительность.

В реальном прогоне обнаружено смешение «зашпатлёванной» поверхности со штукатуркой. В таксономии v2 добавлено отдельное основание `putty` (теперь 17 оснований), уточнён промпт v2. [Первый прогон и обнаруженная ошибка](../../reports/classification/live-2026-10-24/README.md), [повторный тест с точным списком оснований](../../reports/classification/live-2026-10-24-putty-fix/README.md).

## CPU в полном Compose

`--num-threads 8` передаётся как `num_thread` провайдеру Ollama; без параметра
используется автоматический выбор. Квота всего контейнера Ollama по умолчанию
составляет 8 CPU; сервис classifier лишь отправляет запрос и проверяет ответ.
Для локального smoke используйте один товар с `--num-ctx 8192 --num-threads 8`,
запуская его отдельно от объяснений смет. Результаты повторной проверки — в
[отчёте ресурсов](../../reports/deployment/2026-10-29/resource-limits/README.md).
