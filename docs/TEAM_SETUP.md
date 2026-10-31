# Запуск для команды

После clone ветки `backend` все 20 JPEG уже находятся в `data/inspirations/`; Git LFS, личные каталоги и отдельное скачивание не требуются. Основной путь — Docker по `docs/deploy.md`; конфигурация описана в `docs/CONFIG.md`. Пользовательские Windows-launcher удалены. Стандартный Maven Wrapper сохранён.

Для локальной разработки нужны JDK 21, Node.js 22.12+ или 24, Python 3.12+ и Docker для MongoDB. Создайте конфигурацию `make init-config`, заполните Mongo credentials и запустите `make dependencies`.

Соберите Java (`cd backend && ./mvnw package`, на Windows `mvnw.cmd package`), из корня импортируйте `make backend-import-catalog`, `make backend-import-rules`, `make backend-import-inspirations`, затем `make backend`.

Python: создайте venv, установите `pip install -r llm/requirements.txt`. PYTHONPATH должен включать `llm`, `llm/classification`, `llm/estimation`; launcher `make llm` выставляет его автоматически и использует общий env-loader. В env можно задать PYTHON и JAVA_BIN, если стандартные команды отсутствуют в PATH. Локальная Ollama задаётся LOCAL_OLLAMA_URL.

Frontend: `make install`, `make dev`. Proxy обращается только в Java. Без LLM обычные расчёты работают, пояснение показывает недоступность, галерея показывает 20 встроенных фотографий по категориям, а анализ возвращает контролируемый `unavailable`.
