"""Queue integration tests against an explicitly disposable migrated PostgreSQL database.
Run with CLASSIFIER_QUEUE_TESTS=1 and standard libpq PG* variables. No LLM calls.
"""
import os
import unittest
import uuid
from repaircalc_classifier import worker


@unittest.skipUnless(os.environ.get('CLASSIFIER_QUEUE_TESTS') == '1', 'requires disposable PostgreSQL')
class WorkerQueueTests(unittest.TestCase):
    def connect(self):
        import psycopg
        return psycopg.connect('')

    def job(self):
        from psycopg.types.json import Jsonb
        product, version, job = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
        with self.connect() as conn:
            conn.execute("INSERT INTO repaircalc.admin_product(id,source_namespace,external_id,current_version_id,created_by,updated_by) VALUES (%s,'worker-test',%s,%s,'test','test')", (product, str(product), version))
            conn.execute("INSERT INTO repaircalc.admin_product_version(id,product_id,version,snapshot,created_by) VALUES (%s,%s,1,%s,'test')", (version, product, Jsonb({'name': 'Queue test', 'sourceNamespace': 'worker-test', 'externalId': str(product)})))
            conn.execute("INSERT INTO repaircalc.admin_classification_job(id,product_id,version_id,input,created_by) VALUES (%s,%s,%s,%s,'test')", (job, product, version, Jsonb({'name': 'Queue test'})))
        return job

    def row(self, job):
        from psycopg.rows import dict_row
        with self.connect() as conn, conn.cursor(row_factory=dict_row) as cur:
            cur.execute('SELECT * FROM repaircalc.admin_classification_job WHERE id=%s', (job,))
            return cur.fetchone()

    def test_lease_fences_stale_result_and_recovers_after_disconnect(self):
        job = self.job()
        with self.connect() as conn:
            old = worker.claim(conn)
        self.assertEqual(old['id'], job)
        with self.connect() as conn:
            self.assertIsNone(worker.claim(conn))
            conn.execute("UPDATE repaircalc.admin_classification_job SET lease_until=now()-interval '1 second' WHERE id=%s", (job,))
        with self.connect() as conn:
            new = worker.claim(conn)
            self.assertNotEqual(old['lease_token'], new['lease_token'])
            self.assertEqual(worker.complete(conn, old, {'test': 'stale'}), 0)
            self.assertEqual(worker.complete(conn, new, {'test': 'fresh'}), 1)
        self.assertEqual(self.row(job)['result'], {'test': 'fresh'})

    def test_model_errors_are_bounded_and_do_not_lose_input(self):
        job = self.job()
        def fail(_):
            raise ValueError('private model output must not be stored in error')
        for attempt in range(1, 4):
            self.assertTrue(worker.run_one('', fail))
            row = self.row(job)
            self.assertEqual(row['attempts'], attempt)
            self.assertEqual(row['input'], {'name': 'Queue test'})
            self.assertEqual(row['error_code'], 'classification_failed')
            if attempt < 3:
                self.assertEqual(row['status'], 'queued')
                with self.connect() as conn:
                    conn.execute('UPDATE repaircalc.admin_classification_job SET available_at=now() WHERE id=%s', (job,))
        self.assertEqual(self.row(job)['status'], 'failed')
        with self.connect() as conn:
            self.assertIsNone(worker.claim(conn))

    def test_last_attempt_crash_reaches_terminal_state(self):
        job = self.job()
        with self.connect() as conn:
            worker.claim(conn)
            conn.execute("UPDATE repaircalc.admin_classification_job SET attempts=3,lease_until=now()-interval '1 second' WHERE id=%s", (job,))
        with self.connect() as conn:
            self.assertIsNone(worker.claim(conn))
        self.assertEqual(self.row(job)['status'], 'failed')
        self.assertEqual(self.row(job)['error_code'], 'worker_lost')

    def test_two_consumers_do_not_claim_same_job(self):
        first, second = self.job(), self.job()
        with self.connect() as a, self.connect() as b:
            one, two = worker.claim(a), worker.claim(b)
            self.assertEqual({one['id'], two['id']}, {first, second})
            self.assertEqual(worker.complete(a, one, {'test': 'one'}), 1)
            self.assertEqual(worker.complete(b, two, {'test': 'two'}), 1)


if __name__ == '__main__':
    unittest.main()
