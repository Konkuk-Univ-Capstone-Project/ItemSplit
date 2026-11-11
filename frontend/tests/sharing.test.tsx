import assert from 'node:assert/strict';
import { test } from 'node:test';
import { renderToStaticMarkup } from 'react-dom/server';
import App from '../src/App';
import * as settlementApi from '../src/features/settlement/api';

test('public route bypasses private auth storage even with a malformed or expired local session', t => {
  const previousWindow = globalThis.window, previousStorage = globalThis.localStorage;
  Object.defineProperty(globalThis,'window',{configurable:true,value:{location:{pathname:'/share/settlements/'+'a'.repeat(43)}}});
  Object.defineProperty(globalThis,'localStorage',{configurable:true,value:{getItem:()=>{throw new Error('private auth was touched');}}});
  t.after(()=>{Object.defineProperty(globalThis,'window',{configurable:true,value:previousWindow});Object.defineProperty(globalThis,'localStorage',{configurable:true,value:previousStorage});});
  for(const path of ['/share/settlements/'+ 'a'.repeat(43),'/share/settlements','/share/settlements/%broken','/share/settlements/'+ 'a'.repeat(43)+'/extra']) {
    window.location.pathname=path;assert.match(renderToStaticMarkup(<App/>),/공유 정산/);
  }
});
test('owner share issuance is a bodyless authenticated POST scoped to the exact snapshot', async t => {
  let sent: any;
  t.mock.method(globalThis,'fetch',async(url,options)=>{sent={url,options};return new Response(JSON.stringify({success:true,data:{token:'a'.repeat(43),expiresAt:'2026-11-25',shareUrl:'/share/settlements/'+'a'.repeat(43)}}));});
  assert.equal(typeof settlementApi.issueSnapshotShare,'function');
  await settlementApi.issueSnapshotShare('owner-token',1,9);
  assert.equal(sent.url,'/api/rooms/1/settlement-snapshots/9/share-token');
  assert.equal(sent.options.method,'POST');assert.equal(sent.options.body,undefined);
  assert.equal(sent.options.headers.get('Authorization'),'Bearer owner-token');
});

import { parseSharedPath, SharedSettlementController } from '../src/features/sharedSettlement/controller';
import { SharedSettlementView } from '../src/features/sharedSettlement/SharedSettlementPage';
import { SnapshotShareController } from '../src/features/settlement/shareController';
import { SnapshotShareView } from '../src/features/settlement/SnapshotShare';
const shareToken='a'.repeat(43);
const saved={snapshotId:9,roomName:'Saved dinner',version:2,status:'CONFIRMED',confirmedAt:'2026-11-11T12:00:00',reopenedAt:null,expiresAt:'2026-11-25T12:00:00',
  members:[{memberId:1,displayName:'Saved Alice',paid:999,burden:500,net:499},{memberId:2,displayName:'Saved Bob',paid:1,burden:500,net:-499}],
  transfers:[{fromMemberId:2,toMemberId:1,amount:499,status:'PENDING'}]};
const response=(data:unknown,status=200,reason?:string)=>new Response(JSON.stringify({success:status<400,data,error:status>=400?{message:'failed',details:[{reason}]}:null}),{status});
test('public path parser rejects broken encodings missing token and extra segments without throwing',()=>{
  assert.equal(parseSharedPath('/share/settlements/'+shareToken),shareToken);
  for(const path of ['/share/settlements','/share/settlements/','/share/settlements/%broken','/share/settlements/'+shareToken+'/extra']) assert.equal(parseSharedPath(path),null);
});
test('public reads send no bearer and clear snapshot on pending failure or token disposal',async t=>{
  let sent:any, resolve!:(r:Response)=>void, pending=false;
  t.mock.method(globalThis,'fetch',async(url,options)=>{sent={url,options};if(pending)return new Promise<Response>(r=>{resolve=r;});return response(saved);});
  const c=new SharedSettlementController(shareToken);await c.refresh();
  assert.equal(c.getSnapshot().snapshot?.snapshotId,9);assert.equal(sent.options.headers.has('Authorization'),false);
  assert.equal(sent.options.cache,'no-store');assert.equal(sent.options.referrerPolicy,'no-referrer');
  pending=true;const refresh=c.refresh();assert.equal(c.getSnapshot().snapshot,null);assert.equal(c.getSnapshot().mode,'loading');
  resolve(response(null,404));await refresh;assert.equal(c.getSnapshot().mode,'unavailable');assert.equal(c.getSnapshot().snapshot,null);
  const late=c.refresh();c.dispose();resolve(response(saved));await late;assert.equal(c.getSnapshot().snapshot,null);
});
test('public read-only view shows completion and reopened version warning; pending and expired hide old names',()=>{
  const html=renderToStaticMarkup(<SharedSettlementView view={{mode:'ready',snapshot:{...saved,status:'REOPENED',reopenedAt:'2026-11-11T13:00:00',transfers:[{...saved.transfers[0],status:'COMPLETED'}]},error:''}} onRefresh={()=>{}}/>);
  assert.match(html,/Saved Alice/);assert.match(html,/499원/);assert.match(html,/완료/);assert.match(html,/재개방/);assert.match(html,/2차/);
  assert.doesNotMatch(html,/>완료 표시<|>완료 취소<|>재개방<|영수증|이미지|이메일/);
  for(const mode of ['loading','unavailable','error'] as const){const state=renderToStaticMarkup(<SharedSettlementView view={{mode,snapshot:saved,error:'failed'}} onRefresh={()=>{}}/>);assert.doesNotMatch(state,/Saved Alice|499원/);}
});
test('owner share controller prevents duplicate issue and discards old links during rotation, failures and disposed scopes',async t=>{
  let resolve!:(r:Response)=>void, calls=0;
  t.mock.method(globalThis,'fetch',()=>{calls++;return new Promise<Response>(r=>{resolve=r;});});
  let conflict=0;const c=new SnapshotShareController('owner',1,9,()=>{conflict++;});
  const first=c.issue();await c.issue();assert.equal(calls,1);assert.equal(c.getSnapshot().busy,true);
  resolve(response({token:shareToken,expiresAt:'2026-11-25',shareUrl:'/share/settlements/'+shareToken}));await first;
  assert.equal(c.getSnapshot().share?.token,shareToken);
  let copied='';await c.copy('https://itemsplit.test',async text=>{copied=text;});assert.equal(copied,'https://itemsplit.test/share/settlements/'+shareToken);assert.equal(c.getSnapshot().copied,true);
  const rotation=c.issue();assert.equal(c.getSnapshot().share,null);resolve(response(null,409,'SETTLEMENT_VERSION_CONFLICT'));await rotation;
  assert.equal(conflict,1);assert.equal(c.getSnapshot().share,null);assert.ok(c.getSnapshot().error);
  const stale=c.issue();c.dispose();resolve(response({token:shareToken,expiresAt:'2026-11-25',shareUrl:'/share/settlements/'+shareToken}));await stale;
  assert.equal(c.getSnapshot().share,null);
});
test('owner view exposes loading copy manual-copy fallback and renewal explanation',()=>{
  const props={onIssue:()=>{},onCopy:()=>{},origin:'https://itemsplit.test',disabled:false};
  const html=renderToStaticMarkup(<SnapshotShareView {...props} view={{busy:false,share:{token:shareToken,expiresAt:'2026-11-25',shareUrl:'/share/settlements/'+shareToken},error:'복사 실패',copied:false}}/>);
  assert.match(html,/https:\/\/itemsplit.test\/share\/settlements\//);assert.match(html,/링크 복사/);assert.match(html,/이전 링크/);assert.match(html,/2026-11-25/);assert.match(html,/복사 실패/);
  assert.match(renderToStaticMarkup(<SnapshotShareView {...props} view={{busy:true,share:null,error:'',copied:false}}/>),/disabled/);
});
