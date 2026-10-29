# RepairCalc на выделенном сервере

Развёртывание начато 29 октября 2026, проверка продолжена 30 октября по Москве.

- Публичный адрес: http://194.67.95.67
- Сервер: `vm12452`, Ubuntu 26.04 LTS, KVM.
- Проект: `/opt/repaircalc` на сервере.
- Источник: локальная ветка `codex/classification-material-cost-tools`, HEAD
  `49cf0678c0e9c4e86ccad33d9252d06474f41863` плюс незакоммиченные изменения.
- Передан рабочий снимок через rsync: 451 файл, 7 429 342 байта.
  Затем синхронизировано исправление UUID для HTTP.
- Исключены `.git`, локальные env/override, node_modules, target/dist, CPU runtime
  Ollama, крупные JSON/XML/видео и временные файлы редакторов.
- Новый пароль PostgreSQL сгенерирован на сервере; `env/.env` имеет права 0600.
  Пароли SSH и БД в этом отчёте не сохраняются.

## Запуск и доступ

Docker Engine 29.9.2, Compose 5.6.1, NVIDIA Container Toolkit 1.20.1 установлены
из официальных репозиториев. Драйвер NVIDIA 610.57.04 сохранён. Контейнерная
проверка `nvidia-smi` прошла; Ollama обнаружила NVIDIA A10, compute capability 8.6.

Установщик образа облака держал отсутствующие `libnvidia-container1` и
`libnvidia-container-tools` в hold. Они установлены явно в согласованной версии
1.20.1-1; драйвер не переустанавливался.

У Docker настроено зеркало `https://mirror.gcr.io`, поскольку TLS к
`auth.docker.io` из сети сервера завершался таймаутом. Исходная конфигурация
сохранена в `/etc/docker/daemon.json.before-repaircalc-mirror`.

Java и estimator собраны на сервере. npm registry из сети сервера недоступен:
фронтенд собран локально из текущего кода с `VITE_DATA_MODE=api` и
`VITE_API_BASE_URL=/api/v1`, затем передан через `docker save` / rsync / `docker load`.
Идентификаторы итоговых образов записаны в `images.txt` на сервере.

Внешний трафик принимает существующий Caddy на порту 80. Правило для IP находится
в `/etc/caddy/sites/repaircalc.caddyfile`. Существующие маршруты Jupyter по токену
сохранены. Фронтенд опубликован только на `127.0.0.1:8080`; Java, estimator,
PostgreSQL и Ollama не публикуют порты на хост. По выбору пользователя пока HTTP
по IP, домен и HTTPS не подключены.

Команды на сервере:

```bash
cd /opt/repaircalc
./compose.sh ps
./compose.sh logs --tail=100 backend estimator frontend ollama
./compose.sh up -d --no-build --wait
./compose.sh restart frontend
./compose.sh down
```

`compose.sh` подключает базовый Compose, серверный override, GPU override и профиль
`llm`. Контейнеры используют `restart: unless-stopped`, Docker включён в автозапуск.
`down` сохраняет volumes. Не добавляйте `-v`, если нужно сохранить базу и веса.

## Исправление HTTP и проверки

Браузер выявил пустую страницу: `crypto.randomUUID()` недоступен в небезопасном
HTTP-контексте по IP. `frontend/src/domain/id.ts` использует native UUID при наличии,
иначе создаёт UUID v4 через `crypto.getRandomValues`. Обновлены три места вызова.
Проверка типов и 41 тест фронтенда прошли; production-образ пересобран и установлен.

`public-smoke-result.json`: внешняя проверка прошла. Статические файлы, каталог
из 12 товаров, request ID, расчёт плитки (16 упаковок / 34560.00 RUB), совпадение
ответов Java и estimator, HTTP 422 при неправильном вводе.

В браузере через публичный адрес проверен расчёт краски: площадь 20 м², запас 10%,
2 слоя, 2 банки / 4980.00 RUB. Снимок: `public-calculation.jpg`.

## Модель

Официальный образ Ollama 0.40.13 использует A10 через GPU override. Из сети сервера
TLS к хранилищу весов зависает при стандартном согласовании. `download-model.py`
загружает официальный манифест и его blobs через curl с TLS 1.2 и HTTP/1.1,
проверяет размеры и SHA-256, затем публикует манифест в named volume.
Проверка сертификатов включена. Общий объём: 8 021 618 941 байт.

Результаты загрузки и реального inference сохраняются отдельно:
`model-download.json`, `model-list.txt`, `estimate-result.json`, `gpu-telemetry.csv`.

Реальный прогон прошёл: 127.63 секунды вместе с холодным запуском, ровно один
`calculate_material_cost`, непустое пояснение со статусом `unverified_model_text`.
Позиции, материалы, работы, итог и версии совпали с отдельным детерминированным
расчётом Java. Итог краски — 4980.00 RUB. `ollama ps` подтвердил `100% GPU` и
контекст 8192; в телеметрии первой проверки пик GPU — 91%, VRAM — 9299 MiB.

Повтор через публичный интерфейс завершился готовой сметой и видимым пояснением
модели. `browser-result.txt` сохраняет DOM результата, `public-ai-result.jpg` и
`public-ai-full.jpg` — снимки. Модель может включать Markdown и JSON в свободное
пояснение; интерфейс показывает текст как непроверенный, отдельно от суммы Java.

## Границы результата

Используется учебный bootstrap-каталог и учебные нормы. Развёртывание не добавляет
отсутствующие API общей сметы и рекомендаций. Проверенный пользовательский сценарий
— каталог и расчёт отдельного товара; состояние ИИ фиксируется отдельным прогоном.
Доступ по HTTP публичный, пользовательская аутентификация в проекте не добавлена.

Источники процедур: [Docker Engine](https://docs.docker.com/engine/install/ubuntu/),
[NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html).
