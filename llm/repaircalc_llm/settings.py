import os
from dataclasses import dataclass
from pathlib import Path

DATA = Path(os.environ.get("DATA_DIR", Path(__file__).resolve().parents[2] / "data"))


def integer(name, default, minimum=1, maximum=2**31-1):
    value = int(os.environ.get(name, default))
    if not minimum <= value <= maximum:
        raise ValueError(f"Invalid {name}")
    return value


@dataclass(frozen=True)
class Settings:
    token: str
    host: str = "127.0.0.1"
    port: int = 8010
    ollama: str = "http://127.0.0.1:11434"
    model: str = "gemma4:12b"
    timeout: int = 600
    concurrency: int = 1
    body_limit: int = 12 * 1024 * 1024
    response_limit: int = 2 * 1024 * 1024
    context: int = 16384
    predict: int = 4096
    threads: int = 8
    socket_timeout: int = 30

    @classmethod
    def environment(cls):
        from urllib.parse import urlsplit
        token = os.environ.get("INTERNAL_SERVICE_TOKEN", "")
        url = os.environ.get("OLLAMA_URL", "http://127.0.0.1:11434").rstrip("/")
        parsed = urlsplit(url)
        if len(token) < 32 or parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username:
            raise ValueError("Set a service token of at least 32 characters and a valid Ollama URL")
        return cls(token=token, host=os.environ.get("LLM_ADDRESS", "127.0.0.1"),
                   port=integer("LLM_PORT", 8010, maximum=65535), ollama=url,
                   model=os.environ.get("OLLAMA_MODEL", "gemma4:12b"),
                   timeout=integer("OLLAMA_TIMEOUT_SECONDS", 600, maximum=1800),
                   concurrency=integer("LLM_CONCURRENCY", 1, maximum=16),
                   body_limit=integer("LLM_BODY_MAX_BYTES", 12582912),
                   response_limit=integer("OLLAMA_RESPONSE_MAX_BYTES", 2097152),
                   context=integer("OLLAMA_CONTEXT_LENGTH", 16384),
                   predict=integer("OLLAMA_NUM_PREDICT", 4096),
                   threads=integer("OLLAMA_NUM_THREADS", 8, maximum=256),
                   socket_timeout=integer("LLM_SOCKET_TIMEOUT_SECONDS", 30, maximum=300))
