"""Terminal health/progress reporting. stdout remains reserved for validated JSONL."""
import json
import sys
import threading
import time
from urllib.parse import urlsplit
from urllib.request import urlopen

from langchain_core.callbacks import BaseCallbackHandler


def validate_host(host):
    parsed = urlsplit(host)
    if parsed.scheme not in ('http', 'https') or not parsed.netloc or parsed.query or parsed.fragment:
        raise ValueError('--host must be an http(s) URL without query or fragment')
    return host.rstrip('/')


def read_json(host, endpoint, timeout):
    with urlopen(validate_host(host) + endpoint, timeout=timeout) as response:
        payload = json.load(response)
    if not isinstance(payload, dict) or payload.get('error'):
        raise ValueError(f'invalid Ollama response: {payload}')
    models = payload.get('models')
    if not isinstance(models, list) or any(not isinstance(m, dict) for m in models):
        raise ValueError('Ollama response must contain a models array')
    return models


def same_model(requested, entry):
    def canonical(name):
        return name if ':' in name.rsplit('/', 1)[-1] else name + ':latest'
    return any(canonical(requested) == canonical(entry[k])
               for k in ('name', 'model') if isinstance(entry.get(k), str))


def loaded_state(host, model, timeout=3):
    try:
        models = read_json(host, '/api/ps', timeout)
        return any(same_model(model, m) for m in models), None
    except (OSError, ValueError) as error:
        return None, str(error)


def server_status(host, model, timeout=3):
    """A reachable server / installed model / loaded model are distinct states."""
    validate_host(host)
    state = {'host': host, 'model': model, 'reachable': False,
             'installed': None, 'loaded': None, 'error': None, 'runtime_error': None}
    try:
        models = read_json(host, '/api/tags', timeout)
    except (OSError, ValueError) as error:
        state['error'] = str(error)
        return state
    state.update(reachable=True, installed=any(same_model(model, m) for m in models))
    state['loaded'], state['runtime_error'] = loaded_state(host, model, timeout)
    return state


class RequestMonitor(BaseCallbackHandler):
    """LangChain invokes token callbacks while ChatOllama aggregates its stream."""

    def __init__(self, *, host, model, label='', live=False, quiet=False,
                 interval=10, health_timeout=3, stream=None):
        self.host, self.model, self.label = host, model, label
        self.live, self.quiet = live, quiet
        self.interval, self.health_timeout = interval, health_timeout
        self.stream = stream if stream is not None else sys.stderr
        self.lock = threading.RLock()
        self.stop = threading.Event()
        self.thread = None
        self.started = None
        self.first_fragment = None
        self.last_fragment = None
        self.fragments = self.characters = 0
        self.phase = 'waiting'
        self.finished = None
        self.preview_open = False
        self.api_metadata = {}

    def _line(self, message):
        if self.quiet:
            return
        with self.lock:
            if self.preview_open:
                self.stream.write('\n')
                self.preview_open = False
            elapsed = time.monotonic() - self.started if self.started is not None else 0
            self.stream.write(f'[{elapsed:7.1f}s] {message}\n')
            self.stream.flush()

    def __enter__(self):
        self.started = time.monotonic()
        self._line(f'ЗАПРОС {self.label}: ожидаю первый фрагмент '
                   '(очередь / загрузка модели / обработка входа)')
        if not self.quiet:
            self.thread = threading.Thread(target=self._heartbeat, daemon=True)
            self.thread.start()
        return self

    def _heartbeat(self):
        while not self.stop.wait(self.interval):
            loaded, error = loaded_state(self.host, self.model, self.health_timeout)
            with self.lock:
                if self.stop.is_set():
                    return
                # The live text is itself progress; avoid splitting JSON keys with
                # heartbeat lines while new text is arriving normally.
                if (self.live and self.phase == 'generating' and self.last_fragment is not None
                        and time.monotonic() - self.last_fragment < self.interval and not error):
                    continue
                runtime = ('да' if loaded else 'нет') if loaded is not None else 'неизвестно'
                message = {'waiting': 'ОЖИДАНИЕ ПЕРВОГО ФРАГМЕНТА',
                           'generating': 'ОТВЕТ ПОСТУПАЕТ',
                           'validating': 'ПРОВЕРКА ОТВЕТА'}.get(self.phase, self.phase)
                message += (f'; модель в памяти: {runtime}; фрагментов: {self.fragments}; '
                            f'символов: {self.characters}')
                if self.last_fragment is not None:
                    message += f'; без новых фрагментов: {time.monotonic() - self.last_fragment:.1f}s'
                if error:
                    message += '; /api/ps недоступен (это не доказывает остановку inference)'
                self._line(message)

    def on_llm_new_token(self, token, **kwargs):
        if not token:
            return
        with self.lock:
            if self.first_fragment is None:
                self.first_fragment = time.monotonic() - self.started
                self.phase = 'generating'
                self._line('ПЕРВЫЙ ФРАГМЕНТ: модель начала отдавать ответ')
                if self.live and not self.quiet:
                    self._line('LIVE: черновой JSON, ещё не проверен')
            self.fragments += 1
            self.characters += len(token)
            self.last_fragment = time.monotonic()
            if self.live and not self.quiet:
                self.stream.write(token)
                self.stream.flush()
                self.preview_open = True

    def validating(self):
        with self.lock:
            self.phase = 'validating'
            self._line('ОТВЕТ ПОЛУЧЕН: проверяю завершение, схему и цитаты')

    def finish(self, status, metadata=None, error=None):
        with self.lock:
            if self.finished is not None:
                return
            self.finished = time.monotonic() - self.started
            self.phase = status
            self.api_metadata = metadata or {}
            self.stop.set()
            text = {'ok': 'ГОТОВО: схема и цитаты проверены',
                    'review': 'ТРЕБУЕТ ПРОВЕРКИ: схема и цитаты корректны, есть смысловая неоднозначность',
                    'error': 'ОШИБКА', 'cancelled': 'ПРЕРВАНО'}.get(status, status)
            if error:
                text += ': ' + str(error)
            tokens = self.api_metadata.get('eval_count')
            if tokens is not None:
                text += f'; выходных токенов API: {tokens}'
            self._line(text)

    def snapshot(self):
        with self.lock:
            return {'status': self.phase,
                    'elapsed_seconds': round(self.finished if self.finished is not None
                                             else time.monotonic() - self.started, 3),
                    'first_fragment_seconds': round(self.first_fragment, 3)
                    if self.first_fragment is not None else None,
                    'fragments': self.fragments, 'received_characters': self.characters,
                    'output_tokens': self.api_metadata.get('eval_count')}

    def __exit__(self, kind, error, traceback):
        if error is not None:
            self.finish('cancelled' if isinstance(error, KeyboardInterrupt) else 'error', error=error)
        elif self.finished is None:
            self.finish('error', error='request ended without validated result')
        self.stop.set()
        if self.thread:
            self.thread.join(timeout=0.1)
        return False
