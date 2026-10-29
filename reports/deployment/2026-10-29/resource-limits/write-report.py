import json
from pathlib import Path
r=Path(__file__).resolve().parent
s=json.loads((r/'summary.json').read_text())
limits=json.loads((r/'effective-limits.json').read_text())
ollama=next(x for x in limits if x['name']=='/repaircalc-ollama-1')
peak=ollama['cgroup']['memory.peak']/1024**3
usage=s['resources']['repaircalc-ollama-1']
response=s['catalogUnderLoad']
report=f'''# Повторный запуск RepairCalc с лимитами ресурсов — 29.10.2026

**Итог: {s['automatedTestsPassed']} автоматических тестов и сквозные HTTP-проверки прошли.
LLM-смета прошла; ответ модели классификатора отклонён проверкой evidence.** Стек оставлен работающим:
[интерфейс](http://127.0.0.1:8080/product?id=30473701-b10b-3d56-a15a-8591c16486c4).

## Конфигурация и причины изменений

После перегрузки компьютера пользователь ограничил Docker Desktop до 14 vCPU и
16 ГБ RAM. Дополнительно установлены индивидуальные cgroup-лимиты сервисов.
По последним пожеланиям пользователя Ollama имеет **8 CPU и 12 GiB RAM**.
Количество потоков в обоих клиентах Ollama задаётся через `num_thread=8`.

| Сервис | Максимум CPU | Максимум RAM | Swap контейнера |
| --- | ---: | ---: | --- |
| Ollama | 8 | 12 GiB | запрещён |
| Java backend | 1 | 768 MiB | запрещён |
| PostgreSQL | 0,5 | 512 MiB | запрещён |
| Python estimator | 0,5 | 256 MiB | запрещён |
| frontend/nginx | 0,25 | 128 MiB | запрещён |
| classifier, только пакетная задача | 0,5 | 384 MiB | запрещён |
| init-data, завершается до старта Java | 1 | 768 MiB | запрещён |

Сумма верхних границ постоянных сервисов — 10,25 CPU / 13,625 GiB RAM;
с classifier — 10,75 CPU / 14 GiB. Это ограничения, не предварительное выделение RAM.
Одна модель и один параллельный запрос; idle-модель выгружается через 60 секунд.
Контекст 8192 токена сохранён. Сравнение Docker HostConfig с реальными
`cpu.max`, `memory.max`, `memory.swap.max` прошло для всех пяти постоянных сервисов.
[Фактические лимиты и OOM-счётчики](effective-limits.json).

Использован существующий локальный CPU-образ `repaircalc-ollama-cpu:local`,
Ollama 0.40.2 из установленного Snap. Его runtime и локальный override исключены
из Git; основной Compose сохраняет официальный образ Ollama 0.40.13.
Веса gemma4:12b уже находились в постоянном volume. Новая загрузка весов не нужна.

## Найденная проблема памяти и исправление

При первом ограниченном прогоне с 11 GiB расчёт сметы прошёл, но переключение на
классификацию завершило дочерний llama-server по OOM: `memory.events` содержал
`oom=1`, `oom_kill=1`; сам контейнер Ollama оставался живым и healthy.
Следовательно, одного Docker healthcheck и `State.OOMKilled=false` недостаточно.
[Ошибка классификации](classification-before-cache-fix.jsonl),
[счётчики до исправления](oom-before-cache-fix.txt),
[лог runner](ollama-runner-failure.log).

В логе перед остановкой runner сохранял состояние предыдущего промпта в RAM.
Bundled llama-server по умолчанию допускает RAM-кэш 8192 MiB и до 32 checkpoints.
Для этой конфигурации отключены `LLAMA_ARG_CACHE_RAM` и
`LLAMA_ARG_CTX_CHECKPOINTS` (оба 0), лимит памяти увеличен до запрошенных 12 GiB.
Лог нового runner подтверждает отключение обоих механизмов.
[Поддерживаемые параметры установленного runtime](llama-server-help.txt),
[финальный лог](ollama-final.log). Аналогичный механизм описан в
[upstream Ollama issue 18264](https://github.com/ollama/ollama/issues/18264).

После изменений повторена именно последовательность **tool-расчёт → классификация**.
На финальном контейнере после обоих сценариев `oom=0`, `oom_kill=0`;
автоматических перезапусков нет. Классификация завершилась ошибкой проверки цитаты,
без остановки runner. Неудачные артефакты сохранены отдельно.

## Автоматические тесты

| Набор | Результат | Доказательство |
| --- | --- | --- |
| Java unit/API/config | 80 / 80 | [XML](java-unit/), [Maven log](java-verify-rerun.log) |
| Java + настоящие PostgreSQL/Testcontainers | 60 / 60 | [XML](java-integration/), [Maven log](java-verify-rerun.log) |
| Frontend Vitest: unit/component/API-contract | 41 / 41 | [лог](frontend-tests.log) |
| Python estimator | 18 / 18 | [лог](estimator-tests.log) |
| Python classifier | 24 / 24 | [лог](classifier-tests.log) |
| Playwright desktop + mobile | 14 / 14 | [JSON](e2e-result.json), [лог](e2e.log) |

Нет пропусков. Playwright проверяет **деморежим Vite**, не реальный Java/LLM API.
Реальное API проверено отдельным smoke ниже. Unit-тесты дополнены проверкой передачи
CPU-потоков в оба обращения estimator и в LangChain/Ollama-клиент classifier.
Java-тесты запускались при 2 CPU / 1536 MiB и 2 доступных JVM CPU;
Python — 1 CPU / 384 MiB; Vitest — 1,5 CPU / 1024 MiB и один worker;
Playwright — один worker, affinity двух host CPU. LLM с этими наборами не пересекалась.
Testcontainers создавал отдельные временные БД, основной volume не использовался.

Первый Java-прогон имел 6 ошибок из-за отсутствовавших `data/*.json` в builder-stage.
Fixtures смонтированы read-only, после этого полный `mvn verify` прошёл.
[Исходный лог](java-verify.log), [исходные XML](java-unit-first-run/).
Вспомогательный Playwright-конфиг сначала был загружен как CommonJS; расширение
заменено на `.mts`, затем все 14 сценариев прошли. Это ошибки тестового окружения,
они не скрыты и не включены в успешный итог.

## Сквозные API и реальные модели

1. Nginx health, SPA и assets; каталог через nginx → Java → PostgreSQL (12 товаров);
   `X-Request-ID`; прямой Java-расчёт и тот же расчёт через Python;
   HTTP 422 для невалидного запроса. Плитка: **16 упаковок / 34560.00 RUB**.
   [Машинный результат](smoke-result.json), [скрипт](../smoke.py).
2. Реальный nginx → estimator → Ollama → Java tool → Ollama.
   Краска, 20 м², 2 слоя, запас 10%: **2 банки / 4980.00 RUB**.
   Числа, строки сметы и версии совпали с детерминированным `/calculate`;
   ровно один tool `calculate_material_cost`. Пояснение непустое, имеет статус
   `unverified_model_text` и не используется как источник сумм.
   Длительность финального запроса: **{s['estimate']['elapsedSeconds']:.2f} с**.
   [Вход](request-paint.json), [ответ](estimate-result.json), [скрипт](estimate-smoke.py).
3. Реальный classifier → Ollama для одной синтетической карточки краски;
   генерация завершилась с HTTP 200 за 331,89 с, но проверка evidence **не прошла**.
   Модель сослалась на `description` с цитатой «Для внутренних работ.»;
   исходник содержит «Для внутренних работ по бетону.» — точного совпадения нет.
   Валидатор вернул `unsupported evidence`, успешная классификация не опубликована.
   Проверка не ослаблялась ради успешного smoke.
   [Результат](classification-result.jsonl), [прогресс](classification-progress.log).
   Это smoke одного товара, **не оценка F1** на размеченном наборе.

## Измеренная нагрузка

Финальная конфигурация измерялась отдельно от неудачного прогона:
**{s['resourceSamples']} снимков** `docker stats`, примерно раз в 5–7 секунд.
Максимум выборки Ollama — **{usage['maxSampledCpuPercent']:.2f}% CPU**
(около {usage['maxSampledCpuPercent']/100:.2f} логических CPU, а не процентов всего компьютера).
Максимум памяти выборки — **{usage['maxSampledMemoryMiB']/1024:.2f} GiB**;
максимум cgroup `memory.peak` — **{peak:.2f} GiB** при лимите 12 GiB.
Пиковый CPU по выборке не является измерением каждого мгновения;
жёсткая квота проверена непосредственно через cgroup.

Во время финального LLM-запроса выполнено {response['count']} запросов каталога:
медиана **{response['medianSeconds']*1000:.1f} мс**, максимум
**{response['maxSeconds']*1000:.1f} мс**, все ответы успешны и содержат 12 товаров.
[Замеры HTTP](responsiveness.json), [финальные ресурсы](resources-final.jsonl),
[замеры до исправления](resources.jsonl).
После окончания запросов отдельно проверены выгрузка idle-модели, здоровье сервисов
и нулевые OOM-счётчики. [Idle-снимок](idle.txt).
Счётчик `memory.events.max` ненулевой: kernel memory.peak достиг лимита 12 GiB,
при этом OOM/kill не было. `docker stats` исключает часть файлового кэша, поэтому
его sampled RAM меньше cgroup peak; свободный запас по sampled RAM не равен
запасу до cgroup-лимита.

## Воспроизведение и границы

[Точные команды](COMMANDS.md), [проверка cgroup](check-limits.py),
[генератор сводки](summarize.py), [машинная сводка](summary.json),
[финальное состояние контейнеров](services-final.json).
Ветка: `{s['source']['branch']}`, HEAD: `{s['source']['head']}`.
Изменения ещё не закоммичены; SHA-256 проверенных файлов сохранены в summary.json.

Ответ классификатора с неточной цитатой требует отдельной работы над качеством
LLM-вывода; это не исправлено данным инфраструктурным изменением. Усиление промпта
или другие меры нужно проверять на размеченном наборе, сохраняя строгую валидацию.

Полный длительный нагрузочный тест, полный каталог, F1, GPU и другой официальный
runtime этим прогоном не проверены. Отключение кэша относится к используемому
llama-server и требует повторной проверки после смены runtime. Численные расчёты
всегда остаются в Java. Стек оставлен запущенным; временные Java-тестовые контейнеры
удалены, данные PostgreSQL и модель сохранены в named volumes.
'''
(r/'README.md').write_text(report)
(r.parent/'README.md').write_text('# Проверка Docker Compose — 29.10.2026\n\nАктуальный повторный запуск после перегрузки компьютера, с Ollama 8 CPU / 12 GiB:\n[полный отчёт и результаты тестов](resource-limits/README.md).\n')
print(r/'README.md')
