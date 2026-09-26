"""Bounded access regression. No model calls or business writes; two demo logins only."""
import argparse,json,http.client
from urllib.parse import urlsplit
from pathlib import Path
import requests

def main():
 p=argparse.ArgumentParser();p.add_argument('--base-url',default='http://127.0.0.1:10010');p.add_argument('--output',type=Path,required=True);p.add_argument('--hidden-id',type=int)
 a=p.parse_args()
 target=urlsplit(a.base_url)
 if target.scheme!='http' or target.hostname!='127.0.0.1' or target.username or target.password or target.query or target.fragment or target.path not in ('','/api'):raise RuntimeError('Only explicit loopback application endpoints are allowed')
 if a.output.exists():raise RuntimeError('Evidence already exists')
 s=requests.Session();s.trust_env=False;checks=[]
 def check(method,path,status,token=None,body=None):
  headers={'Content-Type':'application/json','user-info':'10002','role-info':'1'}
  if token:headers['authorization']=token
  r=s.request(method,a.base_url+path,headers=headers,data=body,timeout=20,allow_redirects=False)
  checks.append({'method':method,'path':path,'http':r.status_code,'expected':status})
  if r.status_code!=status:raise RuntimeError('Unexpected status for '+path)
  if status==200:
   business_code=r.json().get('code')
   checks[-1]['businessCode']=business_code
   if business_code!=200:raise RuntimeError('Unexpected business status for '+path)
  return r
 try:
  # Malformed JSON cannot bind controller forms, even if the routing regression returns.
  for path in ['/users/users','/auth/privileges','/auth/roles','/auth/menus','/catalog/unsupported-category/add','/catalog/unsupported-items/save','/users/unsupported/register','/users/users/detail/true']:
   check('POST',path,404,body='{')
  for path in ['/users/users/10001','/users/users/list?ids=10001,10002','/users/users/10002/password/default','/trade/carts','/ai/session','/catalog/v3/api-docs','/auth/v3/api-docs']:
   check('GET',path,404)
  for path in ['/users/users/me','/trade/requirements','/catalog/admin/products','/ai/runs']:
   check('GET',path,401)
  for role,entry in [('buyer','login'),('merchant','admin/login')]:
   r=check('POST','/auth/accounts/'+entry,200,body=json.dumps({'username':role+'_demo','password':'XucaiDemo2026!','type':1,'rememberMe':False}))
   token=r.json()['data'];check('GET','/users/users/me',200,token)
   check('GET','/users/users/10001',404,token)
   check('GET','/auth/roles/1',404,token)
   check('GET','/catalog/admin/products?page=1&size=20',403 if role=='buyer' else 200,token)
  check('GET','/catalog/products?page=1&size=20',200)
  for origin,expected in [('http://127.0.0.1:5173',200),('http://127.0.0.1:5174',200),('https://untrusted.invalid',403)]:
   r=s.options(a.base_url+'/catalog/products',headers={'Origin':origin,'Access-Control-Request-Method':'GET'},timeout=20,allow_redirects=False)
   allowed=r.headers.get('Access-Control-Allow-Origin')
   checks.append({'method':'OPTIONS','path':'/catalog/products','origin':origin,'http':r.status_code,'expected':expected,'allowOrigin':allowed})
   if r.status_code!=expected or (expected==200 and allowed!=origin) or (expected==403 and allowed is not None):raise RuntimeError('Unexpected CORS policy')
  # Send raw paths without requests normalizing dot segments first. GET only.
  target=urlsplit(a.base_url)
  if target.scheme!='http' or target.hostname!='127.0.0.1':raise RuntimeError('Raw path probes require loopback HTTP')
  for path in ['/catalog/products/../admin/products','/catalog/products/%2e%2e/admin/products','/catalog/products/%252e%252e/admin/products','/catalog/products;x/1','/catalog//products/1','/catalog/products/%2fadmin/products']:
   conn=http.client.HTTPConnection(target.hostname,target.port,timeout=20)
   try:
    conn.request('GET',target.path+path);r=conn.getresponse();r.read()
    checks.append({'method':'RAW_GET','path':path,'http':r.status,'expected':[400,404]})
    if r.status not in (400,404):raise RuntimeError('Alternate path not rejected')
   finally:conn.close()
  if a.hidden_id is not None:
   detail=check('GET',f'/catalog/admin/products/{a.hidden_id}',200,token).json()['data']
   if detail['status']!='HIDDEN':raise RuntimeError('Chosen product is not hidden')
   check('GET',f'/catalog/products/{a.hidden_id}',404)
   check('GET',f'/catalog/products/{a.hidden_id}/docs',404)
 finally:
  a.output.parent.mkdir(parents=True,exist_ok=True)
  with a.output.open('x',encoding='utf-8') as f:json.dump(checks,f,indent=2)
 print(str(len(checks))+' access checks passed; no business writes or model calls')
if __name__=='__main__':main()
