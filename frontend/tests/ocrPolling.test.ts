import assert from 'node:assert/strict';
import { test } from 'node:test';
import * as polling from '../src/ocrPolling';
import { ApiError } from '../src/api';

const flush = () => new Promise(resolve => setImmediate(resolve));
const pending = { status: 'PROCESSING' };
const terminal = { status: 'REVIEWABLE' };

function watch(options) {
  assert.equal(typeof polling.watchOcrStatus, 'function', 'status polling must implement the agreed retry policy');
  return polling.watchOcrStatus({ onResult: () => {}, onWait: () => {}, onError: () => {}, ...options });
}

test('status reads slow after 10 seconds and stop on completion', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 0 });
  const times = [];
  const stop = watch({ read: async () => { times.push(Date.now()); return times.length === 7 ? terminal : pending; } });
  t.after(stop);
  await flush();
  for (let i=0; i<5; i++) { t.mock.timers.tick(2000); await flush(); }
  assert.deepEqual(times, [0,2000,4000,6000,8000,10000]);
  t.mock.timers.tick(4999); await flush(); assert.equal(times.length,6);
  t.mock.timers.tick(1); await flush();
  t.mock.timers.tick(60000); await flush();
  assert.deepEqual(times, [0,2000,4000,6000,8000,10000,15000]);
});

test('rate limited reads honor Retry-After then recover without an OCR POST', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 0 });
  const times = [], results = [], notices = [];
  const stop = watch({ read: async () => {
    times.push(Date.now());
    if (times.length === 1) throw new ApiError('slow down', 429, undefined, 12000);
    return terminal;
  }, onResult: job => results.push(job), onWait: message => notices.push(message) });
  t.after(stop); await flush();
  t.mock.timers.tick(11999); await flush(); assert.equal(times.length,1);
  t.mock.timers.tick(1); await flush();
  assert.deepEqual(times,[0,12000]); assert.deepEqual(results,[terminal]);
  assert.match(notices[0], /12/); assert.equal(notices.at(-1),'');
});

test('429 fallback increases delay while network and server errors retry at most three times', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 0 });
  let calls=0; const errors=[];
  const stop=watch({ read: async () => {
    calls++;
    if(calls <= 2) throw new ApiError('slow',429);
    if(calls === 3) throw new TypeError('network offline');
    throw new ApiError('server down',503);
  }, onError: e=>errors.push(e) });
  t.after(stop); await flush();
  t.mock.timers.tick(5000); await flush(); assert.equal(calls,2);
  t.mock.timers.tick(9999); await flush(); assert.equal(calls,2);
  t.mock.timers.tick(1); await flush(); assert.equal(calls,3);
  for(const ms of [5000,10000,20000]) {t.mock.timers.tick(ms);await flush();}
  assert.equal(calls,6); assert.equal(errors.length,1);
  t.mock.timers.tick(60000); await flush(); assert.equal(calls,6);
});

for(const status of [401,403,404]) test(`status ${status} stops immediately`,async t=>{
  t.mock.timers.enable({apis:['setTimeout']});
  let calls=0;const errors=[];
  const stop=watch({read:async()=>{calls++;throw new ApiError('unavailable',status);},onError:e=>errors.push(e)});
  t.after(stop);await flush();t.mock.timers.tick(60000);await flush();
  assert.equal(calls,1);assert.equal(errors.length,1);
});

test('slow reads never overlap and disposal aborts the request and ignores late results',async t=>{
  t.mock.timers.enable({apis:['setTimeout','Date'],now:0});
  let calls=0,resolveRead,signal;const results=[];
  const stop=watch({read: s=>{calls++;signal=s;return new Promise(resolve=>{resolveRead=resolve;});},onResult:j=>results.push(j)});
  await flush();t.mock.timers.tick(30000);await flush();assert.equal(calls,1);
  stop();assert.equal(signal.aborted,true);resolveRead(pending);await flush();
  t.mock.timers.tick(30000);await flush();assert.equal(calls,1);assert.deepEqual(results,[]);
});

test('disposal cancels a scheduled read',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});
  let calls=0;
  const stop=watch({read:async()=>{calls++;return pending;}});
  await flush();stop();t.mock.timers.tick(60000);await flush();assert.equal(calls,1);
});
