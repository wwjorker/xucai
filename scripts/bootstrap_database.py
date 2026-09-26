"""Create a NEW procurement database set. Offline plan by default; never upgrades existing databases.

MySQL DDL is not transactional. An incomplete checkpoint deliberately cannot be resumed.
Credentials: XUCAI_BOOTSTRAP_HOST/PORT/USER/PASSWORD; use a dedicated empty MySQL instance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SUFFIXES = ('auth', 'user', 'catalog', 'trade', 'ai')
FILES = ('001-slice0.sql', '002-slice1a.sql', '003-slice2a.sql', '004-slice3a.sql',
         '005-slice3b.sql', '006-slice4a.sql', '007-slice4b.sql', '008-slice4c.sql',
         '009-slice5b.sql', '010-ai-model-call-budget.sql', '011-candidate-plan-import.sql',
         '012-procurement-retrieval.sql', '013-direct-purchase.sql', '014-assistant-sessions.sql', '015-assistant-model-calls.sql', '016-assistant-requirement-source.sql')
TOKENS = re.compile(r"(--[^\n]*(?:\n|$)|\s+|'(?:\\.|''|[^'\\])*'|`[^`]+`|[A-Za-z_][A-Za-z_0-9]*|.)", re.S)
OLD_SEED = "INSERT IGNORE INTO ai_model_budget VALUES ('requirement-pilot-v1',30,1,10000000,18)"
NEW_SEED = "INSERT INTO ai_model_budget VALUES ('requirement-pilot-v1',30,0,10000000,0)"


def schemas(prefix):
    if not re.fullmatch(r'xucai_(?:[a-z][a-z0-9]{0,19}_)?', prefix):
        raise ValueError('Prefix must be xucai_ or xucai_<short lowercase name>_')
    return {f'xucai_{s}': prefix+s for s in SUFFIXES}


def statements(source, mapping):
    """Small lexer for these migration files; map identifiers, never quoted content."""
    if '/*' in source or 'DELIMITER' in source.upper():
        raise ValueError('Unsupported SQL syntax; review migration before bootstrap')
    parts = []; result = []
    for token in TOKENS.findall(source):
        if token.startswith('--'): continue
        if token == ';':
            if ''.join(parts).strip(): result.append(''.join(parts).strip())
            parts = []; continue
        if token.startswith("'"): parts.append(token); continue
        ident = token.strip('`')
        if ident in mapping:
            token = '`'+mapping[ident]+'`'
        elif re.fullmatch(r'(?:tj(?:mall)?|xucai)_[A-Za-z0-9_]+', ident):
            raise ValueError('Migration references an unapproved schema identifier')
        parts.append(token)
    if ''.join(parts).strip(): raise ValueError('SQL must end with a semicolon')
    # No IF NOT EXISTS on schemas: a competing creator must fail, never take over.
    return [s.replace('CREATE DATABASE IF NOT EXISTS ', 'CREATE DATABASE ') for s in result]


def plan(prefix='xucai_'):
    mapping = schemas(prefix); steps = []
    for name in FILES:
        raw = (ROOT/'scripts/sql'/name).read_bytes()
        sql = statements(raw.decode('utf-8-sig'), mapping)
        if name.startswith('010-'):
            if sql.count(OLD_SEED) != 1: raise ValueError('Historical seed changed; review required')
            sql[sql.index(OLD_SEED)] = NEW_SEED
        steps.append({'file': name, 'sourceSha256': hashlib.sha256(raw).hexdigest(), 'sql': sql})
    payload = {'format': 1, 'schemas': list(mapping.values()), 'steps': steps,
               'generationSeed': {'maxCalls': 30, 'usedCalls': 0, 'reservedMicroYuan': 0},
               'embeddingSeed': {'maxCalls': 6, 'usedCalls': 0}, 'totalBudgetMicroYuan': 10000000}
    payload['planSha256'] = hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()
    return payload


def save(path, state):
    temporary = path.with_suffix('.tmp')
    with temporary.open('w', encoding='utf-8') as f:
        json.dump(state, f, ensure_ascii=False, indent=2); f.flush(); os.fsync(f.fileno())
    temporary.replace(path)


def inspect_initial(cursor, p):
    names = p['schemas']; ai = names[4]; user = names[1]; trade = names[3]
    cursor.execute(f'SELECT max_calls,used_calls,max_micro_yuan,reserved_micro_yuan FROM `{ai}`.ai_model_budget')
    generation = list(cursor.fetchone())
    if generation != [30, 0, 10000000, 0]: raise RuntimeError('Unexpected fresh generation budget')
    cursor.execute(f'SELECT max_calls,used_calls,used_documents,used_queries,reserved_micro_yuan FROM `{ai}`.retrieval_budget')
    embedding = list(cursor.fetchone())
    if embedding != [6, 0, 0, 0, 0]: raise RuntimeError('Unexpected fresh embedding budget')
    cursor.execute(f'SELECT username FROM `{user}`.user ORDER BY username')
    accounts = [r[0] for r in cursor.fetchall()]
    if accounts != ['buyer_demo', 'merchant_demo']: raise RuntimeError('Demo accounts not initialized')
    cursor.execute('SELECT column_name FROM information_schema.columns WHERE table_schema=%s AND table_name=%s', (trade, 'purchase_order'))
    columns = {r[0] for r in cursor.fetchall()}
    if not {'expires_at','paid_at','shipment_json','shipped_at','completed_at'} <= columns:
        raise RuntimeError('Order lifecycle migrations incomplete')
    cursor.execute('SELECT table_schema,COUNT(*) FROM information_schema.tables WHERE table_schema IN ('+','.join(['%s']*5)+') GROUP BY table_schema', names)
    tables = dict(cursor.fetchall())
    if set(tables) != set(names): raise RuntimeError('Schema set incomplete')
    return {'generation': generation, 'embedding': embedding, 'accounts': accounts, 'tableCounts': tables,
            'orderLifecycleColumnsPresent': True}


def apply(db, p, checkpoint):
    checkpoint = Path(checkpoint)
    if checkpoint.exists(): raise RuntimeError('Checkpoint already exists; never replay bootstrap')
    with db.cursor() as c:
        lock = 'xucai-bootstrap-'+hashlib.sha256('|'.join(p['schemas']).encode()).hexdigest()[:40]
        c.execute('SELECT GET_LOCK(%s,0)', (lock,))
        if c.fetchone()[0] != 1: raise RuntimeError('Another bootstrap holds the database lock')
        try:
            c.execute('SELECT @@server_uuid,VERSION()'); server, version = c.fetchone()
            if not version.startswith('8.'): raise RuntimeError('This bootstrap is verified only for MySQL 8')
            c.execute('SELECT schema_name FROM information_schema.schemata WHERE schema_name IN ('+','.join(['%s']*5)+')', p['schemas'])
            if c.fetchall(): raise RuntimeError('Target schema already exists; existing environments are forbidden')
            state = {'state': 'STARTED', 'serverUuid': server, 'mysqlVersion': version,
                     'planSha256': p['planSha256'], 'schemas': p['schemas'], 'steps': []}
            checkpoint.parent.mkdir(parents=True, exist_ok=True)
            with checkpoint.open('x', encoding='utf-8') as f:
                json.dump(state, f); f.flush(); os.fsync(f.fileno())
            try:
                for step in p['steps']:
                    entry = {'file': step['file'], 'sourceSha256': step['sourceSha256'], 'state': 'INTENT'}
                    state['steps'].append(entry); save(checkpoint, state)
                    for sql in step['sql']: c.execute(sql)
                    entry['state'] = 'DONE'; save(checkpoint, state)
                state['verification'] = inspect_initial(c, p)
                state['state'] = 'COMPLETE'; save(checkpoint, state)
            except BaseException:
                # DDL may already be committed. Preserve intent and never automatically clean up/retry.
                state['state'] = 'INCOMPLETE'; save(checkpoint, state)
                raise
            return state
        finally:
            c.execute('SELECT RELEASE_LOCK(%s)', (lock,))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prefix', default='xucai_')
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--checkpoint', type=Path, default=ROOT/'.runtime/database-bootstrap.json')
    args = parser.parse_args(); p = plan(args.prefix)
    if not args.apply:
        print(json.dumps({'mode': 'OFFLINE_PLAN', 'schemas': p['schemas'], 'planSha256': p['planSha256'],
                          'files': [s['file'] for s in p['steps']], 'generationSeed': p['generationSeed'],
                          'note': 'Only creates absent databases. Does not create a DB login or configure services.'}, indent=2))
        return
    import pymysql
    db = pymysql.connect(host=os.environ['XUCAI_BOOTSTRAP_HOST'], port=int(os.environ.get('XUCAI_BOOTSTRAP_PORT','3306')),
                         user=os.environ['XUCAI_BOOTSTRAP_USER'], password=os.environ['XUCAI_BOOTSTRAP_PASSWORD'],
                         charset='utf8mb4', autocommit=True, connect_timeout=5, read_timeout=60, write_timeout=60)
    try:
        state = apply(db, p, args.checkpoint)
        print(json.dumps({'state': state['state'], 'schemas': state['schemas'], 'verification': state['verification']}, ensure_ascii=False))
    finally: db.close()


if __name__ == '__main__': main()
