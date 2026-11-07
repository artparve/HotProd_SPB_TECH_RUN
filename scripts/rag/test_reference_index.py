import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from scripts.rag.build_reference_index import build, page_chunks


class ReferenceIndexTest(unittest.TestCase):
    def test_draft_opt_in_checksum_page_provenance_and_idempotence(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            pdf = root / 'source.pdf'
            pdf.write_bytes(b'%PDF-test')
            row = {'id': 'test', 'title': 'Test', 'publisher': 'Test', 'status': 'downloaded',
                   'file': 'source.pdf', 'sha256': hashlib.sha256(pdf.read_bytes()).hexdigest(),
                   'review_status': 'draft', 'rights_status': 'not_assessed',
                   'production_index_approved': False}
            manifest = root / 'manifest.json'
            manifest.write_text(json.dumps([row]))
            with self.assertRaises(ValueError):
                build([manifest], root / 'index')
            with patch('scripts.rag.build_reference_index.subprocess.run', return_value=SimpleNamespace(
                    returncode=0, stdout='Первая страница\fВторая страница\f'.encode())):
                path, info = build([manifest], root / 'index', True)
                self.assertEqual(info['productLinks'], 0)
                self.assertFalse(info['usableForDecisions'])
                corpus = json.loads((path / 'corpus.json').read_text())
                self.assertEqual([c['page'] for c in corpus['chunks']], [1, 2])
                self.assertIsNone(corpus['documents'][0]['url'])
                self.assertEqual(build([manifest], root / 'index', True)[0], path)
                pdf.write_bytes(b'%PDF-changed')
                with self.assertRaises(ValueError):
                    build([manifest], root / 'index', True)

    def test_chunks_are_bounded_even_for_long_lines(self):
        chunks = list(page_chunks(('длинная строка ' * 1000) + '\nконец', 1200))
        self.assertTrue(chunks)
        self.assertTrue(all(0 < len(c) <= 1200 for c in chunks))


if __name__ == '__main__':
    unittest.main()
