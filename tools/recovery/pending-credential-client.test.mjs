// Focused refusal/shape tests for pending-credential-client.mjs.
// No browser, no server bind, no network: imports the pure validators
// only (the shim starts only when run directly).
import assert from 'node:assert/strict';
import {
  BIND_HOST, BIND_PORT, RP_ID, MAX_BODY_BYTES, REQUEST_TIMEOUT_MS,
  ERROR_CODES, errorBody, isLoopbackPeer, routeFor, checkOptionsScope,
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

console.log(JSON.stringify({status: 'PASS', checks: 7, scope: 'SHIM_REFUSAL_SHAPE_ONLY', browser: 'NOT_LAUNCHED'}));
