# Проверка сервиса расхода и стоимости

Из корня репозитория, с запущенными Java `127.0.0.1:8000` и Ollama `127.0.0.1:11434`.
Для этих примеров опубликованы `backend/data/catalog-v1.json`, `work-mappings-v1.json`
и `rules-v1.json`; [подготовка БД и импорт](TEAM_SETUP.md).

```bash
curl --fail-with-body http://127.0.0.1:8000/actuator/health/readiness
curl --fail-with-body http://127.0.0.1:11434/api/tags

PYTHONPATH=backend/estimation python3 -m unittest discover -s backend/estimation/tests -v

PYTHONPATH=backend/estimation python3 -m repaircalc_estimator calculate \
  --input backend/estimation/examples/tile.json --output /tmp/tile-estimate.json
PYTHONPATH=backend/estimation python3 -m repaircalc_estimator calculate \
  --input backend/estimation/examples/wallpaper.json --output /tmp/wallpaper-estimate.json
PYTHONPATH=backend/estimation python3 -m repaircalc_estimator estimate \
  --input backend/estimation/examples/paint.json --output /tmp/paint-estimate.json
```

| Пример | need | Упаковки | Куплено | Остаток | total, RUB |
| --- | --- | --- | --- | --- | --- |
| Краска | 5,28 л | 2 банки | 10 л | 4,72 л | 4980.00 |
| Плитка | 22 м² | 16 упаковок | 23,04 м² | 1,04 м² | 34560.00 |
| Обои | 19 полотен | 7 рулонов | 21 полотно | 2 полотна | 13230.00 |

В краске дополнительно должны быть `model="gemma4:12b"`,
`toolCalls=["calculate_material_cost"]`, непустое `explanation`.
Числа проверяйте в `estimate`, а не по свободному тексту модели.
Цены и нормы этих карточек тестовые, это не коммерческая смета.

Проверка синтетической ML-поправки:

```bash
PYTHONPATH=backend/estimation python3 -m repaircalc_estimator train \
  --input backend/estimation/examples/synthetic-orders.json \
  --output /tmp/repaircalc-coefficients.json
PYTHONPATH=backend/estimation python3 -m repaircalc_estimator \
  --correction /tmp/repaircalc-coefficients.json calculate \
  --input backend/estimation/examples/paint-corrected.json --output /tmp/paint-corrected.json
```

Ожидаются `factor="1.1"`, эффективный запас 21%, `baseline.lines[0].need=5.28`,
`estimate.lines[0].need=5.808`. Упаковок по-прежнему две, стоимость 4980.00.
Артефакт из этого примера демонстрационный. Он не обучен на реальных заказах.

HTTP-проверка:

```bash
# Отдельный терминал; Ctrl+C останавливает сервис.
PYTHONPATH=backend/estimation python3 -m repaircalc_estimator serve
```

```bash
curl --fail-with-body http://127.0.0.1:8010/health
curl --fail-with-body http://127.0.0.1:8010/api/v1/materials/calculate \
  -H 'Content-Type: application/json' --data-binary @backend/estimation/examples/tile.json
curl --fail-with-body http://127.0.0.1:8010/api/v1/materials/estimate \
  -H 'Content-Type: application/json' --data-binary @backend/estimation/examples/paint.json
```

Если модель не вызвала инструмент — 502 `tool_call_required`; если она обрезала ответ —
502 `invalid_model_response`. Проверьте поддержку tools у выбранной модели и ресурсы Ollama.
Если объяснение не удалось после расчёта, смета сохраняется и `explanationStatus="unavailable"`.
Ошибки `needs_clarification`, `catalog_unavailable` и `rules_unavailable` требуют исправления
исходных параметров или опубликованных данных, а не повторного придумывания ответа моделью.

Сохранённый [протокол реального прогона 24.10.2026](../../reports/estimation/2026-10-24/README.md).
