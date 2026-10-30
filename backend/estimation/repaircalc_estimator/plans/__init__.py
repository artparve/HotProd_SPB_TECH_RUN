"""Versioned, review-only plan extraction. Never produces a confirmed Room."""
import base64
import binascii
import hashlib
import io
import json
import math
import re
import time
import warnings
from decimal import Decimal
from pathlib import Path

from jsonschema import Draft202012Validator
from PIL import Image, ImageOps, UnidentifiedImageError

from ..service import ServiceError

ROOT = Path(__file__).parent
SCHEMA_VERSION = "room-plan-draft-v1"
PROMPT_VERSION = "room-plan-extraction-v1"
SCHEMA = json.loads((ROOT / "schema.json").read_text(encoding="utf-8"))
PROMPT = (ROOT / "prompt.txt").read_text(encoding="utf-8")
VALIDATOR = Draft202012Validator(SCHEMA)
MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_BODY_BYTES = 12 * 1024 * 1024
MAX_PIXELS = 12_000_000


def prepare_image(data):
    """Decode before inference, preserve resolution, apply EXIF, strip metadata.

    Actual format wins over extension. WebP is supported because supplied JPEG-named
    files are WebP. No scale inference, resize or crop is performed in v1.
    """
    if not isinstance(data, bytes) or not 0 < len(data) <= MAX_FILE_BYTES:
        raise ServiceError("invalid_image_size", "Expected 1..8388608 image bytes", 413)
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as image:
                if image.format not in ("PNG", "JPEG", "WEBP"):
                    raise ServiceError("unsupported_image", "Use PNG, JPEG or WebP", 415)
                if image.width * image.height > MAX_PIXELS:
                    raise ServiceError("invalid_image_size", "Maximum 12000000 pixels", 413)
                if getattr(image, "n_frames", 1) != 1:
                    raise ServiceError("unsupported_image", "Animated images are not supported", 415)
                image.load()
                oriented = ImageOps.exif_transpose(image).convert("RGBA")
                # Transparent plans need a white background, not black dimension lines on black.
                canvas = Image.new("RGB", oriented.size, "white")
                canvas.paste(oriented, mask=oriented.getchannel("A"))
                output = io.BytesIO()
                canvas.save(output, format="PNG")
                encoded = output.getvalue()
                # Bound the payload after decompression/re-encoding as well.
                if len(encoded) > MAX_FILE_BYTES:
                    raise ServiceError("invalid_image_size", "Prepared PNG exceeds 8 MiB", 413)
                return encoded, {"width": canvas.width, "height": canvas.height,
                                 "sha256": hashlib.sha256(data).hexdigest(),
                                 "preparedSha256": hashlib.sha256(encoded).hexdigest(),
                                 "coordinates": "normalized-oriented-image"}
    except ServiceError:
        raise
    except (Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise ServiceError("invalid_image_size", "Image exceeds safe pixel limit", 413) from None
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError):
        raise ServiceError("invalid_image", "Cannot decode a complete image", 422) from None


def decode_request(request):
    if not isinstance(request, dict) or set(request) != {"imageBase64"}:
        raise ServiceError("invalid_request", "Expected only imageBase64", 422)
    encoded = request["imageBase64"]
    if not isinstance(encoded, str) or len(encoded) > 4 * ((MAX_FILE_BYTES + 2) // 3):
        raise ServiceError("invalid_image_size", "Base64 image exceeds 8 MiB", 413)
    try:
        return base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error):
        raise ServiceError("invalid_image", "Expected plain base64 without a data URL", 422) from None


def validate_draft(draft):
    def invalid(message):
        raise ServiceError("invalid_plan_draft", message, 502)

    if next(VALIDATOR.iter_errors(draft), None) is not None:
        invalid("Model draft does not match room-plan-draft-v1")
    normalized = {}
    missing = []
    fields = [(key, draft[key]) for key in ("name", "length", "width", "height")]
    for index, opening in enumerate(draft["openings"]):
        fields.extend((f"openings.{index}.{key}", opening[key]) for key in ("width", "height"))
    for key, field in fields:
        box = field["bbox"]
        if box is not None:
            if (not all(math.isfinite(value) for value in box.values())
                    or box["width"] <= 0 or box["height"] <= 0
                    or box["x"] + box["width"] > 1.000001
                    or box["y"] + box["height"] > 1.000001):
                invalid(f"Invalid evidence box: {key}")
        if field["state"] != "found":
            if field["value"] is not None or field.get("unit") is not None:
                invalid(f"Unknown/conflicting field contains a usable value: {key}")
            normalized[key] = None
            if key != "name":
                missing.append(key)
            continue
        if (not field["label"] or not field["label"].strip() or box is None
                or field["value"] is None):
            invalid(f"Found field needs value, label and bbox: {key}")
        if key == "name":
            if not field["value"].strip() or field["value"] not in field["label"]:
                invalid("Name is absent from its evidence label")
            continue
        unit = field["unit"]
        if unit is None:
            invalid(f"Found dimension has no units: {key}")
        value = Decimal(field["value"].replace(",", "."))
        tokens = re.findall(r"\d+(?:[.,]\d+)?", field["label"])
        if value not in [Decimal(token.replace(",", ".")) for token in tokens]:
            invalid(f"Dimension differs from its evidence label: {key}")
        metres = value * {"mm": Decimal("0.001"), "cm": Decimal("0.01"), "m": Decimal(1)}[unit]
        minimum, maximum = (Decimal(1), Decimal(10)) if key == "height" else (Decimal("0.1"), Decimal(100))
        if not minimum <= metres <= maximum:
            invalid(f"Dimension outside room bounds: {key}")
        normalized[key] = float(metres)
    if draft["shape"] != "rectangle":
        # Do not offer dimensions for direct application to the rectangular calculator.
        for key in ("length", "width"):
            normalized[key] = None
            if key not in missing:
                missing.append(key)
    return {"draft": draft, "dimensionsMetres": {key: normalized[key] for key in ("length", "width", "height")},
            "missingFields": [*missing, "openingsArea"], "requiresConfirmation": True,
            "warnings": [*draft["warnings"],
                         "Подписи, единицы и координаты предложены моделью и требуют проверки.",
                         "Площадь проёмов необходимо подтвердить вручную; пустой список не означает 0 м²."]}


class PlanExtractor:
    def __init__(self, estimator):
        self.estimator = estimator

    def extract(self, request):
        image, metadata = prepare_image(decode_request(request))
        options = {"temperature": 0, "num_ctx": 8192, "num_predict": 3072}
        if self.estimator.num_threads is not None:
            options["num_thread"] = self.estimator.num_threads
        payload = {"model": self.estimator.model, "stream": False, "think": False,
                   "format": SCHEMA, "options": options,
                   "messages": [{"role": "system", "content": PROMPT},
                                {"role": "user", "content": "Извлеки черновик по JSON Schema: " + json.dumps(SCHEMA),
                                 "images": [base64.b64encode(image).decode("ascii")]}]}
        started = time.monotonic()
        response = self.estimator.transport(self.estimator.ollama + "/api/chat", payload, self.estimator.timeout)
        elapsed = round(time.monotonic() - started, 3)
        try:
            if (response.get("done") is not True or response.get("done_reason") == "length"
                    or response["message"].get("tool_calls")):
                raise ValueError()
            draft = json.loads(response["message"]["content"])
        except (KeyError, TypeError, ValueError, AttributeError):
            raise ServiceError("invalid_model_response", "Incomplete or invalid plan response", 502) from None
        result = validate_draft(draft)
        return {**result, "schemaVersion": SCHEMA_VERSION, "promptVersion": PROMPT_VERSION,
                "model": self.estimator.model, "image": metadata, "elapsedSeconds": elapsed,
                "promptSha256": hashlib.sha256(PROMPT.encode()).hexdigest(),
                "schemaSha256": hashlib.sha256(json.dumps(SCHEMA, sort_keys=True).encode()).hexdigest()}
