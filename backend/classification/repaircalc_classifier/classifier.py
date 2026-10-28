"""LangChain chain with provider-native JSON Schema and evidence checks."""
import hashlib
import json
from pathlib import Path

from langchain_core.prompts import ChatPromptTemplate
from langchain_ollama import ChatOllama

from .catalogs import fields
from .schema import Classification, Product, TAXONOMY, validate_evidence

PROMPT = Path(__file__).with_name('prompt.txt').read_text(encoding='utf-8')
# Candidate regexes are audit helpers, not model instructions or classification rules.
MODEL_TAXONOMY = {g: [{k: v for k, v in e.items() if k != 'candidate_pattern'} for e in TAXONOMY[g]]
                  for g in ('work_types', 'surface_locations', 'substrates', 'properties')}


def build_chain(model='gemma4:12b', base_url='http://127.0.0.1:11434', timeout=600,
                num_ctx=16384, num_predict=4096):
    llm = ChatOllama(model=model, base_url=base_url, temperature=0, reasoning=False,
                     num_ctx=num_ctx, num_predict=num_predict,
                     client_kwargs={'timeout': timeout})
    prompt = ChatPromptTemplate.from_messages([
        ('system', PROMPT), ('human', '{product_json}')
    ]).partial(taxonomy=json.dumps(MODEL_TAXONOMY, ensure_ascii=False))
    return prompt | llm.with_structured_output(Classification, method='json_schema', include_raw=True)


def classify(product, chain, *, model, base_url, monitor=None):
    product = Product.model_validate(product)
    normalized = product.model_dump()
    payload = {'product_json': json.dumps({
        'product': normalized, 'evidence_fields': dict(fields(normalized))
    }, ensure_ascii=False)}
    response = chain.invoke(payload, config={'callbacks': [monitor]}) if monitor else chain.invoke(payload)
    if monitor:
        monitor.validating()
    raw = response.get('raw')
    if raw is not None and raw.response_metadata.get('done_reason') == 'length':
        raise ValueError('model output truncated; increase --num-predict')
    if response.get('parsing_error') is not None or response.get('parsed') is None:
        raise ValueError(f'invalid structured output: {response.get("parsing_error")}')
    result = validate_evidence(Classification.model_validate(response['parsed']), product)
    if monitor:
        monitor.finish('review' if result.needs_review else 'ok',
                       metadata=raw.response_metadata if raw is not None else {})
    envelope = {
        'source': product.source, 'source_id': product.source_id, 'cod_good': product.cod_good,
        'taxonomy_version': TAXONOMY['version'], 'prompt_version': 'repair-classifier-prompt-v2',
        'prompt_sha256': hashlib.sha256(PROMPT.encode()).hexdigest(),
        'taxonomy_sha256': hashlib.sha256(json.dumps(TAXONOMY, sort_keys=True, ensure_ascii=False).encode()).hexdigest(),
        'input_sha256': hashlib.sha256(json.dumps(normalized, sort_keys=True, ensure_ascii=False).encode()).hexdigest(),
        'model': model, 'base_url': base_url,
        'response_metadata': raw.response_metadata if raw is not None else {},
        'classification': result.model_dump(mode='json'),
    }
    if monitor:
        envelope['monitoring'] = monitor.snapshot()
    return envelope
