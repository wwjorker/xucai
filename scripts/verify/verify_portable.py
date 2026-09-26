"""Login and read-only portable smoke check. Never creates business data or calls models.
Login does create the normal authentication log/session. Requires requests.
"""
import argparse
import json
from pathlib import Path
import requests


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--base-url',default='http://127.0.0.1:10010')
    p.add_argument('--output',type=Path,required=True);p.add_argument('--expect-model-disabled',action='store_true')
    args=p.parse_args()
    if args.output.exists():raise RuntimeError('Evidence already exists; no requests sent')
    session=requests.Session();session.trust_env=False;checks=[]
    def check(name,path,status=200,token=None,method='GET',body=None):
        r=session.request(method,args.base_url+path,headers={'authorization':token} if token else {},json=body,timeout=20,allow_redirects=False)
        data=r.json();checks.append({'case':name,'http':r.status_code,'code':data.get('code')})
        if r.status_code!=status or (status==200 and data.get('code')!=200):raise RuntimeError(name+' failed; no retry')
        return data.get('data')
    try:
        page=check('public-products','/catalog/products?page=1&size=20')
        if not page['items']:raise RuntimeError('Import demo catalog before this smoke check')
        pid=page['items'][0]['id'];check('public-product-detail',f'/catalog/products/{pid}')
        check('public-sale-batch',f'/trade/sale-items/batch?ids={pid}')
        check('anonymous-merchant-denied','/catalog/admin/products?page=1&size=20',401)
        check('anonymous-me-denied','/users/users/me',401)
        buyer=check('buyer-login','/auth/accounts/login',method='POST',body={'username':'buyer_demo','password':'XucaiDemo2026!','type':1,'rememberMe':False})
        me=check('buyer-me','/users/users/me',token=buyer)
        if str(me['id'])!='10001':raise RuntimeError('Wrong buyer identity')
        refreshed=check('buyer-cookie-refresh','/auth/accounts/refresh?client=buyer')
        refreshed_me=check('buyer-refreshed-token','/users/users/me',token=refreshed)
        if str(refreshed_me['id'])!='10001':raise RuntimeError('Wrong refreshed identity')
        check('buyer-merchant-denied','/catalog/admin/products?page=1&size=20',403,buyer)
        capability=check('buyer-model-capability','/ai/runs/capability',token=buyer)
        if args.expect_model_disabled and capability['available']:raise RuntimeError('Model unexpectedly enabled')
        merchant=check('merchant-login','/auth/accounts/admin/login',method='POST',body={'username':'merchant_demo','password':'XucaiDemo2026!','type':1,'rememberMe':False})
        me=check('merchant-me','/users/users/me',token=merchant)
        if str(me['id'])!='10002':raise RuntimeError('Wrong merchant identity')
        refreshed=check('merchant-cookie-refresh','/auth/accounts/refresh?client=merchant')
        refreshed_me=check('merchant-refreshed-token','/users/users/me',token=refreshed)
        if str(refreshed_me['id'])!='10002':raise RuntimeError('Wrong refreshed merchant identity')
        check('merchant-catalog','/catalog/admin/products?page=1&size=20',token=merchant)
        check('merchant-cannot-use-buyer-ai','/ai/runs/capability',403,merchant)
    finally:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        with args.output.open('x',encoding='utf-8') as f:json.dump(checks,f,ensure_ascii=False,indent=2)
    print(f'{len(checks)} HTTP checks passed; no model calls, no business writes.')


if __name__=='__main__':main()
