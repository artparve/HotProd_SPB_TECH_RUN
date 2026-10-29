"""Durable PostgreSQL queue. Inference runs outside transactions; writes are lease fenced."""
import logging
import os
import threading
import time
import uuid

log = logging.getLogger(__name__)
LEASE_SECONDS = 90
MAX_ATTEMPTS = 3


def claim(conn):
    from psycopg.rows import dict_row
    token = uuid.uuid4()
    with conn.transaction(), conn.cursor(row_factory=dict_row) as cur:
        # A crash during the final attempt must also reach a terminal state.
        cur.execute("""UPDATE repaircalc.admin_classification_job
            SET status='failed',error_code='worker_lost',finished_at=now(),lease_token=NULL,lease_until=NULL
            WHERE status='running' AND lease_until < now() AND attempts >= %s""", (MAX_ATTEMPTS,))
        cur.execute("""WITH candidate AS (
            SELECT id FROM repaircalc.admin_classification_job
            WHERE attempts < %s AND ((status='queued' AND available_at <= now())
                OR (status='running' AND lease_until < now()))
            ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
        ) UPDATE repaircalc.admin_classification_job j
          SET status='running',attempts=attempts+1,lease_token=%s,
              lease_until=now()+make_interval(secs => %s),error_code=NULL
          FROM candidate c WHERE j.id=c.id RETURNING j.*""", (MAX_ATTEMPTS, token, LEASE_SECONDS))
        return cur.fetchone()


def heartbeat(dsn, job, stop):
    import psycopg
    while not stop.wait(20):
        try:
            with psycopg.connect(dsn) as conn:
                changed = conn.execute("""UPDATE repaircalc.admin_classification_job
                    SET lease_until=now()+make_interval(secs => %s)
                    WHERE id=%s AND status='running' AND lease_token=%s AND lease_until > now()""",
                    (LEASE_SECONDS, job['id'], job['lease_token'])).rowcount
                if not changed:
                    return
        except psycopg.Error:
            log.warning('Heartbeat unavailable for job %s', job['id'])


def complete(conn, job, result=None):
    from psycopg.types.json import Jsonb
    succeeded = result is not None
    terminal = succeeded or job['attempts'] >= MAX_ATTEMPTS
    with conn.transaction():
        return conn.execute("""UPDATE repaircalc.admin_classification_job
            SET status=%s,result=%s,error_code=%s,lease_token=NULL,lease_until=NULL,
                finished_at=CASE WHEN %s THEN now() ELSE NULL END,
                available_at=now()+make_interval(secs => %s)
            WHERE id=%s AND status='running' AND lease_token=%s AND lease_until > now()""",
            ('succeeded' if succeeded else 'failed' if terminal else 'queued',
             Jsonb(result) if succeeded else None, None if succeeded else 'classification_failed', terminal,
             15 * job['attempts'], job['id'], job['lease_token'])).rowcount


def run_one(dsn, infer):
    import psycopg
    with psycopg.connect(dsn) as conn:
        job = claim(conn)
    if job is None:
        return False
    stop = threading.Event()
    thread = threading.Thread(target=heartbeat, args=(dsn, job, stop), daemon=True)
    thread.start()
    result = None
    try:
        result = infer(job['input'])
    except Exception:
        # Model text, input and connection strings must not appear in logs.
        log.warning('Classification failed for job %s attempt %s', job['id'], job['attempts'])
    finally:
        stop.set()
        thread.join(timeout=10)
    with psycopg.connect(dsn) as conn:
        complete(conn, job, result)
    return True


def main():
    import psycopg
    from .classifier import build_chain, classify
    logging.basicConfig(level=logging.INFO)
    # libpq parameters avoid string concatenation of credentials into a DSN.
    dsn = os.environ.get('CLASSIFIER_DB_DSN', '')
    model = os.environ.get('OLLAMA_MODEL', 'gemma4:12b')
    host = os.environ.get('OLLAMA_URL', 'http://ollama:11434')
    chain = build_chain(model=model, base_url=host, timeout=600,
                        num_threads=int(os.environ.get('OLLAMA_NUM_THREADS', '8')))
    def infer(product):
        return classify(product, chain, model=model, base_url=host)
    while True:
        try:
            if not run_one(dsn, infer):
                time.sleep(2)
        except psycopg.Error:
            log.warning('Queue temporarily unavailable')
            time.sleep(5)


if __name__ == '__main__':
    main()
