"""Closed vocabulary and local validation, including source evidence integrity."""
import json
from enum import Enum
from pathlib import Path
import os
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, create_model, model_validator

from .catalogs import fields

TAXONOMY = json.loads(Path(os.environ.get('CLASSIFICATION_TAXONOMY', Path(os.environ.get('DATA_DIR', Path(__file__).resolve().parents[3] / 'data')) / 'classification' / 'taxonomy.json')).read_text(encoding='utf-8'))


class StrictModel(BaseModel):
    model_config = ConfigDict(extra='forbid')


class Attribute(StrictModel):
    name: str
    value: str
    unit: str | None = None


class Category(StrictModel):
    id: str
    name: str


class Product(StrictModel):
    source: str
    source_id: str
    cod_good: str | None = None
    name: str = Field(min_length=1)
    description: str = ''
    marketing_description: str = ''
    categories: list[Category] = Field(default_factory=list)
    attributes: list[Attribute] = Field(default_factory=list)


class Evidence(StrictModel):
    field: str = Field(description='Точный путь из evidence_fields входа, например attributes[0].value')
    quote: str = Field(min_length=1, description='Дословная непустая подстрока значения указанного поля')


def label_model(name, group):
    labels = Enum(name + 'Id', {e['id']: e['id'] for e in TAXONOMY[group]}, type=str)
    return create_model(name, __base__=StrictModel,
                        label=(labels, ...), evidence=(list[Evidence], Field(min_length=1)))


WorkLabel = label_model('WorkLabel', 'work_types')
SurfaceLabel = label_model('SurfaceLabel', 'surface_locations')
SubstrateLabel = label_model('SubstrateLabel', 'substrates')


class PropertyJudgment(StrictModel):
    state: Literal['yes', 'no', 'unknown', 'conflict']
    evidence: list[Evidence]

    @model_validator(mode='after')
    def check_evidence(self):
        if self.state == 'unknown' and self.evidence:
            raise ValueError('unknown property must have empty evidence')
        if self.state != 'unknown' and not self.evidence:
            raise ValueError('known property must have evidence')
        if self.state == 'conflict' and len(self.evidence) < 2:
            raise ValueError('conflict needs at least two pieces of evidence')
        if self.state == 'conflict' and len({(e.field, e.quote) for e in self.evidence}) < 2:
            raise ValueError('conflict needs distinct evidence')
        return self


Properties = create_model('Properties', __base__=StrictModel, **{
    e['id']: (PropertyJudgment, Field(description=e['definition'])) for e in TAXONOMY['properties']
})


class Classification(StrictModel):
    scope: Literal['in_scope', 'out_of_scope', 'uncertain']
    work_types: list[WorkLabel]
    surface_locations: list[SurfaceLabel]
    substrates: list[SubstrateLabel]
    properties: Properties
    needs_review: bool
    review_reasons: list[str]

    @model_validator(mode='after')
    def check_consistency(self):
        for labels in (self.work_types, self.surface_locations, self.substrates):
            values = [label.label for label in labels]
            if len(values) != len(set(values)):
                raise ValueError('duplicate labels')
        props = [getattr(self.properties, e['id']) for e in TAXONOMY['properties']]
        if self.scope == 'out_of_scope' and (
            self.work_types or self.surface_locations or self.substrates
            or any(p.state != 'unknown' for p in props)
        ):
            raise ValueError('out_of_scope must have no repair labels or properties')
        if self.scope == 'in_scope' and not self.work_types:
            raise ValueError('in_scope requires at least one supported work type')
        if (self.scope == 'uncertain' or any(p.state == 'conflict' for p in props)) and not self.needs_review:
            raise ValueError('uncertainty or conflicts require review')
        if self.needs_review != bool(self.review_reasons):
            raise ValueError('review flag must agree with nonempty review reasons')
        return self


def validate_evidence(result: Classification, product: Product):
    """Reject invented quotes/paths. Semantic entailment still needs evaluation."""
    source = dict(fields(product.model_dump()))
    judgments = list(result.work_types) + list(result.surface_locations) + list(result.substrates)
    judgments += [getattr(result.properties, e['id']) for e in TAXONOMY['properties']]
    for judgment in judgments:
        for evidence in judgment.evidence:
            if evidence.field not in source or evidence.quote not in source[evidence.field]:
                raise ValueError(f'unsupported evidence at {evidence.field}: {evidence.quote!r}')
    return result
