"""导入器的响应丢失、人工修改和库存保护回归，内存传输替身不访问数据库。"""
import copy
import json
import unittest
from import_catalog import ROOT, Importer, digest, validate, load_manifest, bind_target, normalize_base

ROW=json.loads((ROOT/'scripts/fixtures/catalog-expansion.json').read_text(encoding='utf-8'))[0]


class FakeApi:
    def __init__(self):
        self.product=None; self.docs=[]; self.sale=None; self.writes=[]
        self.lose_create=False; self.edit_at_state=False

    def call(self,path,method='GET',body=None):
        if method!='GET': self.writes.append((path,method))
        if '?' in path: return {'items':[copy.deepcopy(self.product)] if self.product else [],'total':1 if self.product else 0}
        if path=='/catalog/admin/products' and method=='POST':
            self.product={**copy.deepcopy(body),'id':1,'specVersion':1,'status':'DRAFT'}
            if self.lose_create: raise TimeoutError('保存成功但响应丢失')
            return self.product
        if path.endswith('/docs'):
            if method=='POST': self.docs=[{**body,'version':1,'status':'ACTIVE'}]
            return copy.deepcopy(self.docs)
        if path.endswith('/state'):
            if self.edit_at_state: self.product['specVersion']=2
            return copy.deepcopy(self.product)
        if path.endswith('/status'):
            assert int(body['expectedSpecVersion'])==self.product['specVersion']
            self.product['status']=body['status'];return copy.deepcopy(self.product)
        if '/sale-items/' in path:
            if method=='PUT':
                assert self.sale is None
                self.sale={**body,'saleVersion':1}
            return copy.deepcopy(self.sale)
        return copy.deepcopy(self.product)


class ImportTests(unittest.TestCase):
    def test_target_checkpoint_cannot_cross_environments(self):
        state={'items':{}}
        bind_target(state,'http://localhost:20010/')
        self.assertEqual(state['baseUrl'],'http://127.0.0.1:20010')
        with self.assertRaises(RuntimeError): bind_target(state,'http://127.0.0.1:10010')

    def test_legacy_checkpoint_belongs_to_original_gateway(self):
        state={'items':{'original':{'id':1}}}
        with self.assertRaises(RuntimeError): bind_target(state,'http://127.0.0.1:20010')
        bind_target(state,'http://127.0.0.1:10010')

    def test_target_must_be_explicit_local_root(self):
        for url in ('https://example.org','http://127.0.0.1','http://127.0.0.1:1/path',
                    'http://user:pass@127.0.0.1:1','http://127.0.0.1:1?x=y'):
            with self.assertRaises(ValueError): normalize_base(url)
    def setUp(self):
        self.api=FakeApi();self.state={'items':{}};self.saved={}
        self.imp=Importer(self.api,self.state,self.save,True)
    def save(self): self.saved=copy.deepcopy(self.state)
    def test_lost_create_resumes_without_duplicate(self):
        self.api.lose_create=True
        with self.assertRaises(TimeoutError):self.imp.run_row(ROW)
        self.assertTrue(self.saved['items'][ROW['sku']]['createIntent'])
        resumed=Importer(self.api,self.saved,lambda:None,True)
        resumed.run_row(ROW)
        self.assertEqual(sum(path=='/catalog/admin/products' for path,_ in self.api.writes),1)
        self.assertTrue(self.api.sale['onSale'])
    def test_unresolved_create_never_reposts(self):
        self.state['items'][ROW['sku']]={'hash':digest(ROW),'createIntent':True}
        with self.assertRaisesRegex(RuntimeError,'禁止重发'):self.imp.run_row(ROW)
        self.assertEqual(self.api.writes,[])
    def test_replay_preserves_business_stock_price_and_offsale(self):
        self.imp.run_row(ROW)
        self.api.sale.update(stock=7,price=500,onSale=False,saleVersion=9)
        before=copy.deepcopy(self.api.sale);self.api.writes=[]
        self.imp.run_row(ROW)
        self.assertEqual(self.api.sale,before);self.assertEqual(self.api.writes,[])
    def test_unowned_exact_match_is_not_claimed(self):
        self.api.product={**{k:ROW[k] for k in ('name','brand','series','categoryId','imageUrl','description','specJson')},'id':1,'specVersion':1,'status':'DRAFT'}
        with self.assertRaisesRegex(RuntimeError,'禁止认领'):self.imp.run_row(ROW)
        self.assertEqual(self.api.writes,[])
    def test_edit_during_import_does_not_publish(self):
        self.api.edit_at_state=True
        with self.assertRaisesRegex(RuntimeError,'停止发布'):self.imp.run_row(ROW)
        self.assertFalse(any(p.endswith('/status') for p,_ in self.api.writes));self.assertIsNone(self.api.sale)
    def test_changed_manifest_stops(self):
        self.imp.run_row(ROW);self.api.writes=[]
        with self.assertRaisesRegex(RuntimeError,'清单内容已变'):self.imp.run_row({**ROW,'price':100})
        self.assertEqual(self.api.writes,[])
    def test_dry_run_never_writes(self):
        Importer(self.api,self.state,self.save,False).run_row(ROW)
        self.assertEqual(self.api.writes,[]);self.assertEqual(self.saved,{})
    def test_entire_manifest_valid_and_unique(self):
        rows=json.loads((ROOT/'scripts/fixtures/catalog-expansion.json').read_text(encoding='utf-8'))
        validate(rows);self.assertEqual(len(rows),15)
        with self.assertRaises(ValueError):validate(rows+[rows[0]])

    def test_initial_and_full_manifest_keep_existing_expansion_exact(self):
        self.assertEqual(5,len(load_manifest('initial')))
        all_rows=load_manifest('all');self.assertEqual(20,len(all_rows))
        self.assertEqual(load_manifest('expansion'),all_rows[5:])
        self.assertEqual(20,len({r['sku'] for r in all_rows}))
        self.assertEqual(20,len({r['name'] for r in all_rows}))

    def test_initial_five_use_same_safe_import_and_replay_contract(self):
        for row in load_manifest('initial'):
            api=FakeApi();state={'items':{}}
            imp=Importer(api,state,lambda:None,True);imp.run_row(row)
            api.sale.update(stock=3,onSale=False,price=100,saleVersion=9)
            before=copy.deepcopy(api.sale);api.writes=[]
            imp.run_row(row)
            self.assertEqual(before,api.sale);self.assertEqual([],api.writes)


if __name__=='__main__':unittest.main(verbosity=2)
