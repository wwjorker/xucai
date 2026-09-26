"""New local environment: one manual procurement lifecycle, no model calls.

Default is an offline plan. --execute requires an empty transaction environment
and exactly the 20 imported demo products. Evidence is exclusive, never overwritten.
An incomplete run requires investigation; no automatic mutation retry/resume.
"""
import argparse
import json
from pathlib import Path
import sys
import uuid
import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from import_catalog import normalize_base, atomic_save


def require(condition, message):
    if not condition: raise RuntimeError(message)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    mode=parser.add_mutually_exclusive_group()
    mode.add_argument('--execute',action='store_true')
    mode.add_argument('--resume',action='store_true')
    parser.add_argument('--base-url',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args(); base=normalize_base(args.base_url)
    require(base!='http://127.0.0.1:10010','Do not run fresh-environment acceptance against the existing demo gateway')
    if args.resume:
        state=json.loads(args.output.read_text(encoding='utf-8'))
        require(state.get('baseUrl')==base,'Checkpoint target differs')
        require(state.get('complete'),'Incomplete run: investigate recorded intents; never resend blindly')
        print('Already COMPLETE; zero HTTP requests.');return
    if not args.execute:
        print(json.dumps({'mode':'PLAN_ONLY','baseUrl':base,'modelCalls':0,
                          'creates':'1 requirement, 1 draft, 1 release, 1 order, 1 resolved consultation'}));return
    args.output.parent.mkdir(parents=True,exist_ok=True)
    state={'baseUrl':base,'complete':False,'modelCalls':0,'humanReviewed':False,
           'reviewActor':'script acting as demo merchant','checks':[],'steps':{}}
    with args.output.open('x',encoding='utf-8') as f:json.dump(state,f)
    def save():atomic_save(args.output,state)
    session=requests.Session();session.trust_env=False
    def call(label,method,path,token=None,body=None,status=200):
        r=session.request(method,base+path,headers={'authorization':token} if token else {},
                          json=body,timeout=30,allow_redirects=False)
        state['checks'].append({'label':label,'method':method,'path':path,'http':r.status_code,'expected':status});save()
        require(r.status_code==status,label+': unexpected HTTP '+str(r.status_code))
        if status==200:
            data=r.json();require(data.get('code')==200,label+': application error')
            return data.get('data')
    def step(label,path,token,body=None):
        require(label not in state['steps'],'Duplicate mutation step')
        entry={'phase':'POST_INTENT','path':path,'body':body};state['steps'][label]=entry;save()
        result=call(label,'POST',path,token,body)
        entry.update(phase='DONE',response=result);save();return result
    def login(name,admin=False):
        return call('login-'+name,'POST','/auth/accounts/'+('admin/login' if admin else 'login'),
                    body={'username':name,'password':'XucaiDemo2026!','type':1,'rememberMe':False})
    merchant=login('merchant_demo',True);buyer=login('buyer_demo')
    require(not call('model-unavailable','GET','/ai/runs/capability',buyer)['available'],'Model must be unavailable; configuration is checked separately')
    for path in ('/trade/admin/requirements','/trade/admin/purchase-orders'):
        require(int(call('empty-transaction-environment','GET',path,merchant)['total'])==0,'Transaction environment is not empty')
    catalog=call('catalog20','GET','/catalog/products?page=1&size=100')
    require(int(catalog['total'])==20 and len(catalog['items'])==20,'Expected exactly 20 published demo products')
    selected=[]
    for model,quantity in (('M100',2),('K120',1)):
        candidates=[p for p in catalog['items'] if model in p['name']]
        require(len(candidates)==1,'Demo product ambiguous: '+model)
        pid=candidates[0]['id']
        product=call('product-snapshot','GET',f'/catalog/admin/products/{pid}/snapshot',merchant)
        sale=call('sale-before','GET',f'/trade/admin/sale-items/{pid}',merchant)
        docs=product['documents'];require(len(docs)==1,'Expected one official source')
        doc=docs[0];require('USB' in doc['content'] and len(doc['content'])<=1000,'Source must cover USB and fit intact')
        selected.append({'product':product,'sale':sale,'doc':doc,'quantity':quantity})
    amount=sum(int(p['sale']['price'])*p['quantity'] for p in selected)
    require(amount==14700,'Unexpected demo price; inspect import')
    req=step('create-requirement','/trade/requirements',buyer,{'title':'全新环境人工采购演示',
        'rawText':'用于办公文档录入，买2件鼠标和1件键盘，总预算200元，必须USB有线连接。',
        'structured':{'budgetCents':20000,'quantity':3,'categoryIds':[3,4],
            'items':[{'categoryId':3,'quantity':2},{'categoryId':4,'quantity':1}],
            'usage':'办公文档录入','mustHave':['USB有线连接'],'optional':[]}})
    items=[]
    for entry in selected:
        p,s,d=entry['product'],entry['sale'],entry['doc']
        items.append({'productId':p['id'],'expectedSpecVersion':p['specVersion'],'expectedSaleVersion':s['saleVersion'],
                      'quantity':entry['quantity'],'evidence':[{'docId':d['id'],'version':d['version'],
                      'quote':d['content'],'note':'保留完整官方摘记及地区键位限制；人工演示核对'}]})
    draft=step('create-manual-draft',f'/trade/admin/requirements/{req["id"]}/plans',merchant,
        {'requestId':str(uuid.uuid4()),'expectedRequirementRevision':req['revision'],
         'summary':'M100鼠标2件与K120键盘1件，演示总价147元；地区键位需实际采购前核对。','items':items})
    require(draft['source']=='MERCHANT' and int(draft['totalAmount'])==amount,'Wrong draft source or total')
    review=call('review-ready','GET',f'/trade/admin/plans/{draft["id"]}/review',merchant)
    require(review['currentCheck']['status']=='READY','Draft is not ready')
    refs=[{'productId':p['product']['id'],'docId':p['doc']['id']} for p in selected]
    form={'requestId':str(uuid.uuid4()),'note':'脚本代表演示商家核对，不是本人金标。K120地区键位仍需按实际货号确认。',
          'checks':[{'key':key,'verdict':'MET','reason':reason,'references':refs} for key,reason in (
              ('usage','M100基础桌面办公与K120文字数字录入支持本例用途；不涉及性能认证。'),
              ('mustHave:0','两款完整资料均记载USB有线连接；不将无线或向量相似度作为依据。'))]}
    call('buyer-cannot-publish','POST',f'/trade/admin/plans/{draft["id"]}/publish',buyer,form,403)
    published=step('publish-reviewed-plan',f'/trade/admin/plans/{draft["id"]}/publish',merchant,form)
    visible=call('buyer-visible-plan','GET',f'/trade/plans/{published["id"]}',buyer)
    require(visible['snapshot']==published['snapshot'],'Published snapshot differs')
    address={'address':{'recipient':'演示收件人','phone':'13800000000','detail':'本地虚构演示园区（非真实地址）'}}
    confirm=f'/trade/plans/{published["id"]}/confirm'
    order=step('confirm-order',confirm,buyer,address);oid=order['id'];path='/trade/purchase-orders/'+oid;admin='/trade/admin/purchase-orders/'+oid
    require(int(order['totalAmount'])==amount and order['status']=='NO_PAY','Wrong initial order')
    replay=call('confirm-replay','POST',confirm,buyer,address)
    require(replay['id']==oid and replay['snapshot']==order['snapshot'],'Confirm replay changed order')
    call('changed-address-rejected','POST',confirm,buyer,{'address':{**address['address'],'detail':'另一虚构地址'}},409)
    paid=step('demo-pay',path+'/demo-pay',buyer);require(paid['payChannel']=='DEMO','Not demo payment')
    shipment={'carrier':'演示配送','trackingNo':'DEMO-FRESH-001','note':'虚构物流单号；本地演示'}
    call('buyer-cannot-ship','POST',admin+'/ship',buyer,shipment,403)
    shipped=step('merchant-ship',admin+'/ship',merchant,shipment)
    completed=step('buyer-complete',path+'/complete',buyer);require(completed['status']=='COMPLETED','Order incomplete')
    case_id=str(uuid.uuid4())
    step('consultation',path+'/after-sales',buyer,{'requestId':case_id,'reason':'演示咨询USB连接与键盘地区布局，不表示商品故障。'})
    step('resolve-consultation',admin+f'/after-sales/{case_id}/resolve',merchant,
         {'status':'RESOLVED','resolution':'已依据完整资料解释连接及地区布局限制；仅演示记录，未退款、补发或回库。'})
    for label,target,token,body,field,previous in (
        ('pay-replay',path+'/demo-pay',buyer,None,'paidAt',paid),
        ('ship-replay',admin+'/ship',merchant,shipment,'shippedAt',shipped),
        ('complete-replay',path+'/complete',buyer,None,'completedAt',completed)):
        result=call(label,'POST',target,token,body)
        require(result[field]==previous[field] and result['snapshot']==order['snapshot'],'Replay changed order')
    final=call('buyer-final-order','GET',path,buyer)
    require(final==call('merchant-final-order','GET',admin,merchant),'Two views differ')
    require(final['status']=='COMPLETED' and len(final['afterSales'])==1 and final['afterSales'][0]['status']=='RESOLVED','Final lifecycle differs')
    for entry in selected:
        now=call('stock-once','GET',f'/trade/admin/sale-items/{entry["product"]["id"]}',merchant)
        require(int(now['stock'])==int(entry['sale']['stock'])-entry['quantity'],'Stock deducted incorrectly')
    saved=call('requirement-ordered','GET',f'/trade/requirements/{req["id"]}',buyer)
    require(saved['status']=='ORDERED','Requirement status differs')
    state.update(complete=True,orderId=oid,totalAmount=amount,finalOrder=final);save()
    print(json.dumps({'complete':True,'httpChecks':len(state['checks']),'orderId':oid,'modelCalls':0}))


if __name__=='__main__':main()
