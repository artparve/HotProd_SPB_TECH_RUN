import argparse
import json
import math
import sys
from pathlib import Path

from .catalogs import json_products, xml_products
from .classifier import build_chain, classify
from .monitor import RequestMonitor, server_status, validate_host


def positive(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError('expected a positive integer')
    return number


def positive_seconds(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError('expected a finite positive number of seconds')
    return number


def main():
    parser = argparse.ArgumentParser(description='Классификация карточек через LangChain + Ollama JSON Schema')
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--input', type=Path, help='Нормализованная карточка или массив карточек JSON')
    source.add_argument('--json-catalog', type=Path)
    source.add_argument('--xml-catalog', type=Path)
    source.add_argument('--status', action='store_true', help='Проверить Ollama и модель без inference')
    parser.add_argument('--source-id', help='JSON id или XML offer/@id; не cod_good')
    parser.add_argument('--limit', type=positive, default=1, help='Предохранитель: по умолчанию один запрос')
    parser.add_argument('--model', default='gemma4:12b')
    parser.add_argument('--host', default='http://127.0.0.1:11434')
    parser.add_argument('--timeout', type=positive, default=600)
    parser.add_argument('--num-ctx', type=positive, default=16384)
    parser.add_argument('--num-threads', type=positive, help='CPU threads for Ollama')
    parser.add_argument('--num-predict', type=positive, default=4096)
    parser.add_argument('--output', type=Path, help='Новый JSONL-файл; существующий не перезаписывается')
    parser.add_argument('--live', action='store_true', help='Показывать черновой ответ по мере поступления в stderr')
    parser.add_argument('--quiet', action='store_true', help='Отключить сообщения о ходе запроса')
    parser.add_argument('--progress-interval', type=positive_seconds, default=10,
                        help='Интервал прогресса и /api/ps, секунд (по умолчанию 10)')
    parser.add_argument('--health-timeout', type=positive_seconds, default=3,
                        help='Timeout отдельной проверки состояния Ollama')
    args = parser.parse_args()
    if args.live and args.quiet:
        parser.error('--live and --quiet cannot be combined')
    if args.status and (args.output or args.live):
        parser.error('--status cannot be combined with --output or --live')
    output = None
    try:
        validate_host(args.host)
        if not args.quiet and not args.status:
            print(f'ПРОВЕРКА Ollama {args.host}, модель {args.model} ...', file=sys.stderr, flush=True)
        health = server_status(args.host, args.model, args.health_timeout)
        health['status'] = ('unreachable' if not health['reachable'] else
                            'available' if health['installed'] else 'model_missing')
        if args.status:
            print(json.dumps(health, ensure_ascii=False, indent=2))
            return 0 if health['reachable'] and health['installed'] else 1
        if not health['reachable']:
            raise ValueError(f"Ollama недоступен: {health['error']}")
        if not health['installed']:
            raise ValueError(f'Модель {args.model} не установлена в {args.host}; '
                             f'загрузите её на этом сервере Ollama')
        if not args.quiet:
            loaded = ('да' if health['loaded'] else 'нет') if health['loaded'] is not None else 'неизвестно'
            print(f'OLLAMA ДОСТУПЕН; модель установлена; в памяти: {loaded}. '
                  'Наличие в памяти не означает активную генерацию.', file=sys.stderr, flush=True)
        if args.input:
            data = json.loads(args.input.read_text(encoding='utf-8'))
            products = iter(data if isinstance(data, list) else [data])
        elif args.json_catalog:
            products = json_products(args.json_catalog)
        else:
            products = xml_products(args.xml_catalog)
        chain = build_chain(args.model, args.host, args.timeout, args.num_ctx, args.num_predict,
                            num_threads=args.num_threads)
        output = args.output.open('x', encoding='utf-8') if args.output else sys.stdout
        count, failures = 0, 0
        for product in products:
            if args.source_id and str(product['source_id']) != args.source_id:
                continue
            count += 1
            monitor = RequestMonitor(host=args.host, model=args.model,
                                     label=f"{count}/{args.limit} {product.get('source_id', '?')}",
                                     live=args.live, quiet=args.quiet,
                                     interval=args.progress_interval, health_timeout=args.health_timeout)
            try:
                with monitor:
                    result = classify(product, chain, model=args.model, base_url=args.host, monitor=monitor)
                result['status'] = 'ok'
            except Exception as error:
                failures += 1
                result = {'status': 'error', 'source': product.get('source'),
                          'source_id': product.get('source_id'), 'error': str(error),
                          'monitoring': monitor.snapshot()}
            print(json.dumps(result, ensure_ascii=False), file=output, flush=True)
            if count >= args.limit:
                break
        if not count:
            raise ValueError('no matching products; check --source-id (source IDs are not cod_good)')
        if not args.quiet:
            print(f'processed={count} errors={failures}', file=sys.stderr)
        return 1 if failures else 0
    except (OSError, ValueError, KeyError) as error:
        print(str(error), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print('Прервано пользователем (Ctrl+C)', file=sys.stderr)
        return 130
    finally:
        if output is not None and output is not sys.stdout:
            output.close()


if __name__ == '__main__':
    raise SystemExit(main())
