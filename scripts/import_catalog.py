"""本机演示商品导入。默认只读检查；--apply 才写入。检查点必须保留。

不改已有商品/资料/售价/库存。创建结果未知时只查找恢复，绝不再次POST。
同一检查点用OS文件锁排他；禁止多个检查点并行导入同一清单。
仅用于本机维护窗口，执行期间商家应停止编辑目录与售卖设置；跨服务
最后一次校验和开售写入不是原子操作，不支持多管理员同时维护。
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.request
from urllib.parse import quote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
FIELDS = ('name', 'brand', 'series', 'categoryId', 'imageUrl', 'description', 'specJson')
DEFAULT_BASE = 'http://127.0.0.1:10010'


def normalize_base(value):
    parsed = urlsplit(value)
    if (parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost')
            or parsed.username or parsed.password or parsed.path not in ('', '/')
            or parsed.query or parsed.fragment or not parsed.port):
        raise ValueError('base-url必须是明确端口的本机HTTP根地址')
    return f'http://127.0.0.1:{parsed.port}'


def bind_target(state, base):
    base = normalize_base(base)
    # Old nonempty checkpoints belong to the former hard-coded gateway.
    existing = state.get('baseUrl', DEFAULT_BASE if state.get('items') else base)
    if normalize_base(existing) != base:
        raise RuntimeError('检查点属于另一网关，禁止切换环境复用；使用独立源码目录')
    state['baseUrl'] = base


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def atomic_save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    with temporary.open('w', encoding='utf-8') as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
        f.flush()
        os.fsync(f.fileno())
    os.replace(temporary, path)


def validate(rows):
    if not isinstance(rows, list) or not rows:
        raise ValueError('商品清单必须是非空数组')
    for key in ('sku', 'name'):
        if len({r[key] for r in rows}) != len(rows):
            raise ValueError(f'重复{key}')
    for r in rows:
        for key, limit in [('name',160),('brand',80),('series',120),('description',4000),('content',30000)]:
            if not isinstance(r[key],str) or not r[key].strip() or len(r[key]) > limit:
                raise ValueError(f'{r["sku"]}: {key}无效')
        if type(r['categoryId']) is not int or not 1 <= r['categoryId'] <= 6:
            raise ValueError('分类必须为1至6')
        if not isinstance(r['specJson'],dict) or not r['specJson'] or not r.get('sourceLocator') or not r.get('sourceCheckedAt'):
            raise ValueError('必须有规格及来源核对记录')
        if not r['sourceUrl'].startswith('https://') or r['sourceUrl'] not in r['content']:
            raise ValueError('正文必须包含HTTPS来源')
        if type(r['price']) is not int or not 1 <= r['price'] <= 100000000 or type(r['stock']) is not int or not 0 <= r['stock'] <= 1000000:
            raise ValueError('演示售价或库存无效')
        if r['imageUrl'] is not None:
            raise ValueError('本清单使用站内分类占位图')


class Api:
    def __init__(self, base_url=DEFAULT_BASE):
        self.base_url = normalize_base(base_url)
        self.token = None
        self.writes = []
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, path, method='GET', body=None):
        headers = {'Content-Type':'application/json'}
        if self.token:
            headers['authorization'] = self.token
        request = urllib.request.Request(self.base_url+path,
            data=json.dumps(body,ensure_ascii=False).encode() if body is not None else None,
            headers=headers, method=method)
        if method != 'GET' and 'login' not in path:
            self.writes.append({'method':method,'path':path})
        with self.opener.open(request,timeout=20) as response:
            payload = json.load(response)
            if response.status != 200 or payload.get('code') != 200:
                raise RuntimeError(f'{method} {path}未成功')
            return payload.get('data')

    def login(self):
        self.token=self.call('/auth/accounts/admin/login','POST',{
            'type':1,'username':'merchant_demo','password':'XucaiDemo2026!','rememberMe':False})


class Importer:
    def __init__(self, api, state, save, apply=False):
        self.api, self.state, self.save, self.apply = api, state, save, apply

    def find(self, row):
        found=[]; page=1
        while True:
            result=self.api.call('/catalog/admin/products?size=100&page='+str(page)+'&keyword='+quote(row['name']))
            found += [p for p in result['items'] if p['name']==row['name']]
            if page*100 >= int(result['total']): break
            page += 1
        if len(found)>1: raise RuntimeError('同名商品不唯一，停止导入：'+row['sku'])
        return found[0] if found else None

    def run_row(self, row):
        record=self.state['items'].get(row['sku'])
        if record and record['hash'] != digest(row):
            raise RuntimeError('清单内容已变，不允许覆盖已导入记录：'+row['sku'])
        product=self.api.call('/catalog/admin/products/'+str(record['id'])) if record and record.get('id') else self.find(row)
        if product and any(product.get(k)!=row[k] for k in FIELDS):
            raise RuntimeError('已有商品内容不一致，保留现场：'+row['sku'])
        if product and not record:
            raise RuntimeError('发现同名商品但无创建检查点，禁止认领：'+row['sku'])
        if not product:
            if record and record.get('createIntent'):
                raise RuntimeError('创建结果未知且尚未查到商品，禁止重发POST；保留检查点后调查：'+row['sku'])
            if not self.apply: return {'sku':row['sku'],'action':'would-create'}
            record={'hash':digest(row),'createIntent':True}
            self.state['items'][row['sku']]=record
            self.save()  # 请求前持久化；即使HTTP响应丢失也不能重复创建
            created=self.api.call('/catalog/admin/products','POST',{k:row[k] for k in FIELDS})
            record['id']=int(created['id']); self.save()
            product=self.api.call('/catalog/admin/products/'+str(record['id']))
            if any(product.get(k)!=row[k] for k in FIELDS):
                raise RuntimeError('创建后商品已被更改，停止：'+row['sku'])
        pid=int(product['id'])
        if not record:
            record={'hash':digest(row),'id':pid}
            self.state['items'][row['sku']]=record
        record['id']=pid
        verified_version=int(product['specVersion'])
        if record.get('specVersion',verified_version)!=verified_version:
            raise RuntimeError('商品规格版本已变，停止：'+row['sku'])
        record['specVersion']=verified_version
        docs=self.api.call(f'/catalog/admin/products/{pid}/docs')
        expected={'docKey':'official-spec','title':row['name']+' 官方规格摘记','sourceUrl':row['sourceUrl'],'content':row['content']}
        if docs and (len(docs)!=1 or docs[0]['status']!='ACTIVE' or any(docs[0].get(k)!=v for k,v in expected.items())):
            raise RuntimeError('已有资料与导入版本不同，禁止覆盖：'+row['sku'])
        sale=self.api.call(f'/trade/admin/sale-items/{pid}')
        if not self.apply:
            return {'sku':row['sku'],'id':pid,'action':'preserve' if docs and sale else 'would-complete'}
        self.save()
        if not docs:
            if record.get('done') or product['status']!='DRAFT':
                raise RuntimeError('资料缺失但商品已进入业务使用，停止：'+row['sku'])
            self.api.call(f'/catalog/admin/products/{pid}/docs','POST',{**expected,'expectedVersion':0})
        current=self.api.call(f'/catalog/admin/products/{pid}/state')
        if int(current['specVersion'])!=verified_version:
            raise RuntimeError('导入期间商品已修改，停止发布：'+row['sku'])
        if current['status']=='DRAFT' and not record.get('done'):
            self.api.call(f'/catalog/admin/products/{pid}/status','PUT',{'status':'PUBLISHED','expectedSpecVersion':verified_version})
        elif current['status']!='PUBLISHED':
            raise RuntimeError('商品状态已改变，禁止自动重新发布：'+row['sku'])
        if sale is None:
            latest=self.api.call(f'/catalog/admin/products/{pid}/state')
            latest_docs=self.api.call(f'/catalog/admin/products/{pid}/docs')
            if int(latest['specVersion'])!=verified_version or latest['status']!='PUBLISHED' or len(latest_docs)!=1 or latest_docs[0]['status']!='ACTIVE' or any(latest_docs[0].get(k)!=v for k,v in expected.items()):
                raise RuntimeError('开售前目录已变，停止：'+row['sku'])
            if record.get('done') or record.get('saleIntent'):
                raise RuntimeError('售卖初始化结果未知或记录丢失，禁止重置：'+row['sku'])
            record['saleIntent']=True; self.save()
            self.api.call(f'/trade/admin/sale-items/{pid}','PUT',{'price':row['price'],'stock':row['stock'],'onSale':True,'expectedSaleVersion':0,'expectedStock':0})
        # 一旦存在售卖记录，库存、售价、上下架均归业务管理，导入器不再写。
        record['done']=True; self.save()
        return {'sku':row['sku'],'id':pid,'action':'ready','existingSalePreserved':sale is not None}


def load_manifest(name):
    files = {'initial': ['catalog-initial.json'], 'expansion': ['catalog-expansion.json'],
             'all': ['catalog-initial.json', 'catalog-expansion.json']}[name]
    rows = [row for file in files for row in json.loads((ROOT/'scripts/fixtures'/file).read_text(encoding='utf-8'))]
    validate(rows)
    return rows


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply',action='store_true')
    parser.add_argument('--base-url',default=DEFAULT_BASE)
    parser.add_argument('--manifest',choices=['initial','expansion','all'],default='expansion')
    parser.add_argument('--limit',type=int)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    rows=load_manifest(args.manifest)
    if args.limit is None: args.limit=len(rows)
    if not 1 <= args.limit <= len(rows): parser.error('limit超出清单范围')
    path=ROOT/'.runtime/catalog-expansion-state.json'
    path.parent.mkdir(parents=True,exist_ok=True)
    # 三种清单共用旧检查点与同一把锁；不能用不同清单绕过排他或认领保护。
    with path.with_suffix('.lock').open('a+b') as lock:
        lock.seek(0); lock.write(b'0'); lock.flush(); lock.seek(0)
        if os.name=='nt':
            import msvcrt
            msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
        else:
            import fcntl
            fcntl.flock(lock.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
        state=json.loads(path.read_text(encoding='utf-8')) if path.exists() else {'items':{}}
        bind_target(state,args.base_url)  # Must fail before login or any business request.
        if args.apply: atomic_save(path,state)
        api=Api(state['baseUrl']); api.login()
        importer=Importer(api,state,lambda:atomic_save(path,state),args.apply)
        result=[importer.run_row(r) for r in rows[:args.limit]]
        atomic_save(args.output,{'apply':args.apply,'baseUrl':state['baseUrl'],'manifestHash':digest(rows),'products':result,'writes':api.writes})
        print(json.dumps({'products':len(result),'writes':len(api.writes),'apply':args.apply}))


if __name__=='__main__':
    main()
