import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import bootstrap_database as b


class FakeCursor:
    def __init__(self, existing=False, fail=False): self.calls=[]; self.existing=existing; self.fail=fail; self.last=''
    def __enter__(self): return self
    def __exit__(self,*args): pass
    def execute(self,sql,args=None):
        self.calls.append(sql); self.last=sql
        if self.fail and sql.startswith('CREATE TABLE'): raise RuntimeError('injected DDL interruption')
    def fetchone(self):
        if 'server_uuid' in self.last: return ('test-server','8.0.36')
        return (1,)
    def fetchall(self): return [('xucai_auth',)] if self.existing else []


class FakeDb:
    def __init__(self,**kw): self.c=FakeCursor(**kw)
    def cursor(self): return self.c


class BootstrapTest(unittest.TestCase):
    def test_new_install_demo_password_matches_both_seed_accounts(self):
        import bcrypt
        import re
        seed = (b.ROOT / 'scripts/sql/001-slice0.sql').read_text(encoding='utf-8')
        hashes = re.findall(r"INSERT INTO xucai_user.user .*?'(\$2a\$[^']+)'", seed)
        self.assertEqual(2, len(hashes))
        for encoded in hashes:
            self.assertTrue(bcrypt.checkpw(b'XucaiDemo2026!', encoded.encode()))
            self.assertFalse(bcrypt.checkpw(b'wrong-demo-password', encoded.encode()))

    def test_wrong_initial_budget_is_runtime_error_even_in_optimized_python(self):
        cursor=FakeCursor()
        cursor.fetchone=lambda: (30,1,10000000,18)
        with self.assertRaises(RuntimeError): b.inspect_initial(cursor,b.plan())

    def test_offline_plan_maps_only_schema_identifiers_and_zeros_new_budget(self):
        p=b.plan('xucai_test_')
        self.assertEqual(16,len(p['steps']))
        self.assertEqual('016-assistant-requirement-source.sql',p['steps'][-1]['file'])
        self.assertTrue(any('CREATE TABLE requirement_assistant_source' in sql for sql in p['steps'][-1]['sql']))
        self.assertEqual(b.NEW_SEED,p['steps'][9]['sql'][2])
        self.assertIn(b.OLD_SEED,(b.ROOT/'scripts/sql/010-ai-model-call-budget.sql').read_text(encoding='utf-8'))
        self.assertEqual(['xucai_test_'+s for s in b.SUFFIXES],p['schemas'])
        self.assertTrue(p['steps'][0]['sql'][1].startswith('CREATE DATABASE `xucai_test_auth`'))

    def test_literals_semicolons_escapes_comments_are_not_remapped(self):
        self.assertEqual(["SELECT 'xucai_ai; -- not a comment', 'it''s;ok', `xucai_test_ai`"],
                         b.statements("-- start\nSELECT 'xucai_ai; -- not a comment', 'it''s;ok', xucai_ai; -- end",b.schemas('xucai_test_')))

    def test_rejects_unsafe_prefix_and_unapproved_schema(self):
        for prefix in ['foreign_', 'xucai_x;_', 'other_', 'xucai_../../_']:
            with self.assertRaises(ValueError): b.plan(prefix)
        with self.assertRaises(ValueError): b.statements('USE xucai_unapproved;',b.schemas('xucai_'))

    def test_existing_schema_is_refused_before_any_ddl_or_checkpoint(self):
        with tempfile.TemporaryDirectory() as d:
            db=FakeDb(existing=True); path=Path(d)/'state.json'
            with self.assertRaises(RuntimeError): b.apply(db,b.plan(),path)
            self.assertFalse(path.exists())
            self.assertFalse(any(s.startswith(('CREATE','INSERT','ALTER','UPDATE')) for s in db.c.calls))

    def test_existing_checkpoint_refuses_even_a_different_empty_server(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'state.json';path.write_text('{}');db=FakeDb()
            with self.assertRaises(RuntimeError): b.apply(db,b.plan(),path)
            self.assertEqual([],db.c.calls)

    def test_ddl_failure_retains_intent_and_never_retries(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'state.json';db=FakeDb(fail=True)
            with self.assertRaises(RuntimeError): b.apply(db,b.plan(),path)
            state=json.loads(path.read_text());self.assertEqual('INCOMPLETE',state['state'])
            self.assertEqual('INTENT',state['steps'][0]['state'])
            calls=len(db.c.calls)
            with self.assertRaises(RuntimeError): b.apply(db,b.plan(),path)
            self.assertEqual(calls,len(db.c.calls))

    def test_complete_checkpoint_and_once_only_execution(self):
        with tempfile.TemporaryDirectory() as d, patch.object(b,'inspect_initial',return_value={'verified':True}):
            path=Path(d)/'state.json';db=FakeDb();state=b.apply(db,b.plan(),path)
            self.assertEqual('COMPLETE',state['state']);self.assertEqual(16,len(state['steps']))
            self.assertTrue(all(s['state']=='DONE' for s in state['steps']))
            with self.assertRaises(RuntimeError): b.apply(FakeDb(),b.plan(),path)


if __name__ == '__main__': unittest.main()
