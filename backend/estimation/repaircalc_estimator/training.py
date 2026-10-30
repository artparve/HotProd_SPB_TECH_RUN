"""Per-product no-intercept least squares: actualConsumed ~= factor * baselineNeed.

Training labels are consumed material in the baseline unit, not purchased packages.
Artifacts are explicitly tied to the Java catalog/rules versions.
"""
import hashlib
import json
from decimal import Decimal, localcontext
from uuid import UUID

from .service import ServiceError, number

KINDS = {"paint", "primer", "covering", "adhesive", "cable"}


def train(rows, min_samples=5):
    if type(min_samples) is not int or min_samples < 2:
        raise ServiceError("invalid_training_data", "min_samples must be at least 2")
    if not isinstance(rows, list) or not rows:
        raise ServiceError("invalid_training_data", "Expected a nonempty JSON array of orders")
    groups, seen = {}, set()
    with localcontext() as ctx:
        ctx.prec = 40
        for row in rows:
            try:
                order = row["orderId"]
                product = str(UUID(row["productId"]))
                kind, unit = row["kind"], row["unit"]
                catalog, rules = row["catalogVersion"], row["rulesVersion"]
                if (kind not in KINDS or not all(isinstance(v, str) and v.strip()
                        for v in (order, unit, catalog, rules)) or (order, product) in seen):
                    raise ValueError()
                seen.add((order, product))
                x = number(row["baselineNeed"], "baselineNeed", 0, 10**12, exclusive=True)
                y = number(row["actualConsumed"], "actualConsumed", 0, 10**12, exclusive=True)
                group = groups.setdefault(product, {"kind": kind, "unit": unit, "catalog": catalog,
                                                     "rules": rules, "pairs": []})
                if (kind, unit, catalog, rules) != tuple(group[k] for k in ("kind", "unit", "catalog", "rules")):
                    raise ValueError()
                group["pairs"].append((x, y))
            except (KeyError, ValueError, TypeError, AttributeError):
                raise ServiceError("invalid_training_data", "Invalid, duplicate or incompatible order row") from None
        entries = {}
        for product, group in groups.items():
            pairs = group.pop("pairs")
            if len(pairs) < min_samples:
                raise ServiceError("insufficient_training_data", f"At least {min_samples} orders required for {product}")
            coefficient = sum(x*y for x, y in pairs) / sum(x*x for x, _ in pairs)
            if not Decimal("0.5") <= coefficient <= Decimal("1.5"):
                raise ServiceError("invalid_training_data", "Coefficient outside supported 0.5..1.5 range")
            mae = sum(abs(y - coefficient*x) for x, y in pairs) / len(pairs)
            entries[product] = {**group, "factor": str(coefficient), "samples": len(pairs), "trainingMAE": str(mae)}
    digest = hashlib.sha256(json.dumps(rows, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    return {"schemaVersion": 1, "version": f"consumption-ols-v1-{digest[:16]}",
            "trainingSha256": digest, "method": "per-product-no-intercept-ols", "products": entries}


def corrected_input(artifact, baseline, values):
    line = baseline["lines"][0]
    product = line["product"]
    try:
        if artifact["schemaVersion"] != 1 or not isinstance(artifact["version"], str) or not artifact["version"]:
            raise ValueError()
        entry = artifact["products"][product["id"]]
        if (product["kind"] not in KINDS or entry["kind"] != product["kind"] or entry["unit"] != product["unit"]
                or entry["catalog"] != baseline["versions"]["catalog"]
                or entry["rules"] != baseline["versions"]["rules"]):
            raise ValueError()
        factor = number(entry["factor"], "factor", Decimal("0.5"), Decimal("1.5"))
        number(entry["samples"], "samples", 2, 10**9, whole=True)
        number(entry["trainingMAE"], "trainingMAE", 0, 10**12)
        # Baseline already includes user reserve. Do not add the same waste twice.
        reserve = (Decimal(100) + Decimal(values["reserve"])) * factor - 100
        number(reserve, "effectiveReserve", 0, 30)
    except (KeyError, TypeError, ValueError):
        raise ServiceError("correction_incompatible", "Coefficient missing or incompatible with product/catalog/rules") from None
    return {**values, "reserve": str(reserve)}, {
        "applied": True, "version": artifact["version"], "factor": str(factor),
        "originalReserve": values["reserve"], "effectiveReserve": str(reserve),
        "samples": entry["samples"], "trainingMAE": entry["trainingMAE"]}
