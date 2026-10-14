// Focused refusal/shape tests for pending-credential-client.mjs.
// No browser, no server bind, no network: imports the pure validators
// only (the shim starts only when run directly).
import assert from 'node:assert/strict';
import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {
  BIND_HOST, BIND_PORT, RP_ID, MAX_BODY_BYTES, REQUEST_TIMEOUT_MS, HEADERS_TIMEOUT_MS,
  ERROR_CODES, errorBody, isLoopbackPeer, routeFor, checkOptionsScope,
  nestPrf, createSerializer, applyServerBounds,
} from './pending-credential-client.mjs';

assert.equal(BIND_HOST, '127.0.0.1');
assert.equal(BIND_PORT, 18044);
assert.equal(RP_ID, 'localhost');
assert.ok(MAX_BODY_BYTES >= 4096 && MAX_BODY_BYTES <= 1048576);
assert.ok(REQUEST_TIMEOUT_MS >= 1000 && REQUEST_TIMEOUT_MS <= 300000);

// Fixed routes only.
assert.equal(routeFor('GET', '/health'), 'health');
assert.equal(routeFor('POST', '/create'), 'create');
assert.equal(routeFor('POST', '/get'), 'get');
for (const [method, path] of [
  ['GET', '/create'], ['POST', '/health'], ['GET', '/get'],
  ['POST', '/'], ['DELETE', '/create'], ['POST', '/eval'],
  ['POST', '/rpc'], ['GET', '/credential'], ['POST', '/Create'],
]) assert.equal(routeFor(method, path), null, `${method} ${path}`);

// Loopback peers only (IPv4/IPv6 forms); everything else refused.
for (const peer of ['127.0.0.1', '::1', '::ffff:127.0.0.1']) assert.ok(isLoopbackPeer(peer), peer);
for (const peer of ['192.168.1.2', '10.0.0.1', '::ffff:192.168.1.2', '', undefined, null]) {
  assert.ok(!isLoopbackPeer(peer), String(peer));
}

// RP/origin scope: localhost only.
assert.equal(checkOptionsScope('create', {challenge: 'c', rp: {id: 'localhost'}}), null);
assert.equal(checkOptionsScope('get', {challenge: 'c'}), null);
assert.equal(checkOptionsScope('get', {challenge: 'c', rpId: 'localhost'}), null);
for (const options of [
  {challenge: 'c', rp: {id: 'remanence.hryshyn.dev'}},
  {challenge: 'c', rp: {id: 'evil.test'}},
  {challenge: 'c', rp: {}},
  {challenge: 'c'},
]) assert.equal(checkOptionsScope('create', options), 'RP_SCOPE_REFUSED', JSON.stringify(options));
for (const options of [{challenge: 'c', rpId: 'remanence.hryshyn.dev'}, {challenge: 'c', rpId: ''}]) {
  assert.equal(checkOptionsScope('get', options), 'RP_SCOPE_REFUSED', JSON.stringify(options));
}
// Malformed shapes (never reach a ceremony).
for (const options of [null, undefined, 42, 'x', [], {}, {challenge: ''}, {challenge: 42}, {rp: {id: 'localhost'}}]) {
  assert.equal(checkOptionsScope('create', options), 'BAD_SHAPE', String(options));
  assert.equal(checkOptionsScope('get', options), 'BAD_SHAPE', String(options));
}
assert.equal(checkOptionsScope('eval', {challenge: 'c'}), 'BAD_SHAPE');
assert.equal(checkOptionsScope('rpc', {challenge: 'c'}), 'BAD_SHAPE');

// Coarse fixed error codes only; unknown codes never emitted.
for (const code of Object.values(ERROR_CODES)) assert.deepEqual(errorBody(code), {error: code});
assert.throws(() => errorBody('PRF_BYTES'), /UNKNOWN_ERROR_CODE/);
assert.throws(() => errorBody('Accepted'), /UNKNOWN_ERROR_CODE/);

// Genuine-PRF nesting wire (T191 consumes this shape): a real 32-byte
// first (43-char base64url) nests under
// credential.clientExtensionResults.prf.results.first; anything else
// is omitted — never hardcoded/synthesized, input never mutated.
{
  const genuine = 'AA'.repeat(21) + 'A'; // 43 chars, canonical alphabet
  assert.equal(genuine.length, 43);
  const base = {id: 'c', response: {}, clientExtensionResults: {}};
  const nested = nestPrf(base, genuine);
  assert.deepEqual(nested.clientExtensionResults, {prf: {results: {first: genuine}}});
  assert.equal(nested.id, 'c');
  assert.deepEqual(base.clientExtensionResults, {});
  for (const bad of [null, undefined, '', 'QUJD', 'A'.repeat(44), 'A'.repeat(42), 42, 'A+'.repeat(22)]) {
    assert.deepEqual(nestPrf(base, bad), base, String(bad));
  }
}
// The in-page ceremony mirrors the same wire (static pin: it must nest
// under clientExtensionResults and must not emit a sibling prf field,
// which T191 would discard).
{
  const source = await readFile(new URL('./pending-credential-client.mjs', import.meta.url), 'utf8');
  assert.ok(source.includes('credential.clientExtensionResults = {prf: {results: {first:'));
  assert.ok(!/out\.prf\s*=/.test(source));
}

// True serialization: busy held until the work REALLY settles.
{
  const deferred = () => {
    let settle;
    const promise = new Promise(resolve => { settle = resolve; });
    return {promise, settle};
  };
  const s = createSerializer();
  assert.equal(s.isBusy(), false);
  const first = deferred();
  const run1 = s.run(() => first.promise, 50);
  assert.equal(s.isBusy(), true);
  await assert.rejects(s.run(() => Promise.resolve('late'), 50), /CEREMONY_BUSY/);
  first.settle('one');
  assert.equal(await run1, 'one');
  assert.equal(s.isBusy(), false);
  // Timeout rejects the caller but the mutex stays held until the
  // orphan settles; meanwhile newcomers get BUSY, never overlap.
  const hanging = deferred();
  const run2 = s.run(() => hanging.promise, 5);
  await assert.rejects(run2, /CEREMONY_TIMEOUT/);
  assert.equal(s.isBusy(), true);
  await assert.rejects(s.run(() => Promise.resolve('overlap'), 50), /CEREMONY_BUSY/);
  hanging.settle('late-result');
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(s.isBusy(), false);
  assert.equal(await s.run(() => Promise.resolve('next'), 50), 'next');
  // Work errors release and surface coarsely.
  await assert.rejects(s.run(() => Promise.reject(Error('CEREMONY_FAILED')), 50), /CEREMONY_FAILED/);
  assert.equal(s.isBusy(), false);
}

// Explicit server bounds apply to a real (unbound) server object.
{
  const server = applyServerBounds(http.createServer());
  assert.equal(server.headersTimeout, HEADERS_TIMEOUT_MS);
  assert.equal(server.requestTimeout, REQUEST_TIMEOUT_MS + 5000);
  await new Promise(resolve => server.close(resolve));
}

console.log(JSON.stringify({status: 'PASS', checks: 11, scope: 'SHIM_REFUSAL_SHAPE_ONLY', browser: 'NOT_LAUNCHED'}));
