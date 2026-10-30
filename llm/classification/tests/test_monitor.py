import contextlib
import io
import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from repaircalc_classifier.__main__ import main
from repaircalc_classifier.classifier import build_chain, classify
from repaircalc_classifier.monitor import RequestMonitor, same_model, server_status
from test_classifier import answer, product


class HealthTests(unittest.TestCase):
    def test_server_down_missing_cold_and_loaded_are_distinct(self):
        with patch('repaircalc_classifier.monitor.read_json', side_effect=OSError('refused')):
            state = server_status('http://localhost', 'test')
        self.assertFalse(state['reachable'])
        self.assertIsNone(state['installed'])
        with patch('repaircalc_classifier.monitor.read_json', side_effect=[[], []]):
            state = server_status('http://localhost', 'test')
        self.assertTrue(state['reachable'])
        self.assertFalse(state['installed'])
        with patch('repaircalc_classifier.monitor.read_json', side_effect=[[{'name': 'test:latest'}], []]):
            state = server_status('http://localhost', 'test')
        self.assertTrue(state['installed'])
        self.assertFalse(state['loaded'])
        with patch('repaircalc_classifier.monitor.read_json', return_value=[{'model': 'test:latest'}]):
            self.assertTrue(server_status('http://localhost', 'test')['loaded'])

    def test_runtime_probe_failure_does_not_mean_server_down(self):
        with patch('repaircalc_classifier.monitor.read_json', side_effect=[
                [{'name': 'test:latest'}], OSError('ps timeout')]):
            state = server_status('http://localhost', 'test')
        self.assertTrue(state['reachable'])
        self.assertTrue(state['installed'])
        self.assertIsNone(state['loaded'])
        self.assertIn('ps timeout', state['runtime_error'])

    def test_aliases_and_invalid_api_response(self):
        self.assertTrue(same_model('test', {'name': 'test:latest'}))
        self.assertFalse(same_model('test:12b', {'name': 'test:12b-extra'}))
        with patch('repaircalc_classifier.monitor.urlopen', return_value=io.BytesIO(b'{"models": {}}')):
            self.assertFalse(server_status('http://localhost', 'test')['reachable'])
        with self.assertRaises(ValueError):
            server_status('file:///tmp/model', 'test')


class ProgressTests(unittest.TestCase):
    def test_live_heartbeat_is_quiet_for_fresh_text_and_returns_on_stall(self):
        output = io.StringIO()
        monitor = RequestMonitor(host='http://localhost', model='test', live=True, interval=10, stream=output)
        monitor.started = monitor.last_fragment = 0
        monitor.phase = 'generating'
        for now, expect_status in [(5, False), (20, True)]:
            output.seek(0)
            output.truncate()
            monitor.stop = Mock()
            monitor.stop.wait.side_effect = [False, True]
            monitor.stop.is_set.return_value = False
            with patch('repaircalc_classifier.monitor.loaded_state', return_value=(True, None)), \
                    patch('repaircalc_classifier.monitor.time.monotonic', return_value=now):
                monitor._heartbeat()
            self.assertEqual('ОТВЕТ ПОСТУПАЕТ' in output.getvalue(), expect_status)

    def test_wait_heartbeat_and_no_output_after_close(self):
        printed = threading.Event()
        class Output(io.StringIO):
            def write(self, text):
                result = super().write(text)
                if 'ОЖИДАНИЕ ПЕРВОГО ФРАГМЕНТА' in text:
                    printed.set()
                return result
        output = Output()
        probed = threading.Event()
        def loaded(*args):
            probed.set()
            return False, None
        with patch('repaircalc_classifier.monitor.loaded_state', side_effect=loaded):
            with RequestMonitor(host='http://localhost', model='test', interval=0.01, stream=output) as monitor:
                self.assertTrue(probed.wait(1))
                self.assertTrue(printed.wait(1))
                monitor.finish('ok')
        text = output.getvalue()
        self.assertIn('ожидаю первый фрагмент', text)
        self.assertIn('модель в памяти: нет', text)
        self.assertIn('ГОТОВО', text)
        self.assertTrue(monitor.stop.is_set())
        self.assertFalse(monitor.thread.is_alive())
        self.assertEqual(output.getvalue(), text)

    def test_stream_is_visible_before_response_is_complete(self):
        output = io.StringIO()
        content = json.dumps(answer(), ensure_ascii=False)
        prefix, suffix = content[:40], content[40:]
        def chat(**kwargs):
            yield {'message': {'role': 'assistant', 'content': prefix}, 'done': False}
            self.assertIn(prefix, output.getvalue())  # Already visible, before yielding final chunk.
            self.assertNotIn('ГОТОВО', output.getvalue())
            yield {'message': {'role': 'assistant', 'content': suffix}, 'done': False}
            yield {'message': {'role': 'assistant', 'content': ''}, 'done': True,
                   'done_reason': 'stop', 'eval_count': 42}
        with patch('ollama.Client.chat', side_effect=chat):
            with RequestMonitor(host='http://localhost', model='test', live=True, stream=output) as monitor:
                result = classify(product(), build_chain('test'), model='test', base_url='http://localhost',
                                  monitor=monitor)
        self.assertEqual(result['monitoring']['status'], 'ok')
        self.assertEqual(result['monitoring']['fragments'], 2)
        self.assertEqual(result['monitoring']['received_characters'], len(content))
        self.assertEqual(result['monitoring']['output_tokens'], 42)
        self.assertIsNotNone(result['monitoring']['first_fragment_seconds'])
        self.assertIn('черновой JSON', output.getvalue())
        self.assertIn('проверяю завершение, схему и цитаты', output.getvalue())
        self.assertIn('ГОТОВО', output.getvalue())

    def test_invalid_live_output_is_never_success(self):
        output = io.StringIO()
        def chat(**kwargs):
            yield {'message': {'role': 'assistant', 'content': '{"invalid":true}'},
                   'done': True, 'done_reason': 'stop'}
        with patch('ollama.Client.chat', side_effect=chat):
            with self.assertRaises(ValueError):
                with RequestMonitor(host='http://localhost', model='test', live=True, stream=output) as monitor:
                    classify(product(), build_chain('test'), model='test', base_url='http://localhost',
                             monitor=monitor)
        self.assertEqual(monitor.snapshot()['status'], 'error')
        self.assertIn('ОШИБКА', output.getvalue())
        self.assertNotIn('ГОТОВО', output.getvalue())

    def test_cancellation_review_and_quiet(self):
        output = io.StringIO()
        with self.assertRaises(KeyboardInterrupt):
            with RequestMonitor(host='http://localhost', model='test', stream=output) as monitor:
                raise KeyboardInterrupt()
        self.assertEqual(monitor.phase, 'cancelled')
        self.assertIn('ПРЕРВАНО', output.getvalue())
        output = io.StringIO()
        with RequestMonitor(host='http://localhost', model='test', quiet=True, stream=output) as monitor:
            monitor.on_llm_new_token('partial')
            monitor.finish('review')
        self.assertEqual(output.getvalue(), '')
        self.assertEqual(monitor.snapshot()['status'], 'review')


class CliTests(unittest.TestCase):
    def invoke(self, argv):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch('sys.argv', ['classifier', *argv]), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = main()
        return code, stdout.getvalue(), stderr.getvalue()

    def test_status_without_input_or_inference(self):
        health = {'reachable': True, 'installed': True, 'loaded': False}
        with patch('repaircalc_classifier.__main__.server_status', return_value=health), \
                patch('repaircalc_classifier.__main__.build_chain') as chain:
            code, stdout, _ = self.invoke(['--status'])
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(stdout)['status'], 'available')
        chain.assert_not_called()

    def test_cli_jsonl_is_clean_in_live_mode(self):
        content = json.dumps(answer(), ensure_ascii=False)
        def chat(**kwargs):
            for chunk in [content[:50], content[50:]]:
                yield {'message': {'role': 'assistant', 'content': chunk}, 'done': False}
            yield {'message': {'role': 'assistant', 'content': ''}, 'done': True,
                   'done_reason': 'stop', 'eval_count': 50}
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'product.json'
            path.write_text(product().model_dump_json(), encoding='utf-8')
            with patch('repaircalc_classifier.__main__.server_status', return_value={
                    'reachable': True, 'installed': True, 'loaded': True}), \
                    patch('ollama.Client.chat', side_effect=chat):
                code, stdout, stderr = self.invoke(['--input', str(path), '--live'])
        self.assertEqual(code, 0)
        self.assertEqual(len(stdout.splitlines()), 1)
        self.assertEqual(json.loads(stdout)['status'], 'ok')
        self.assertIn('черновой JSON', stderr)
        self.assertIn('ПЕРВЫЙ ФРАГМЕНТ', stderr)

    def test_missing_model_fails_before_inference(self):
        with patch('repaircalc_classifier.__main__.server_status', return_value={
                'reachable': True, 'installed': False, 'loaded': False}), \
                patch('repaircalc_classifier.__main__.build_chain') as chain:
            code, stdout, stderr = self.invoke(['--input', 'not-read.json'])
        self.assertEqual(code, 1)
        self.assertIn('не установлена', stderr)
        self.assertEqual(stdout, '')
        chain.assert_not_called()


if __name__ == '__main__':
    unittest.main()
