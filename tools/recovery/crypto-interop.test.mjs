// Requires the synthetic fixture written by AccountRecoveryCryptoTest.
import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
import {openRecovery, unb64, b64, publicCredential} from '../../server/src/remanence/recovery/web/crypto.mjs';
const fixture=JSON.parse(await readFile(new URL('../../android/core/crypto/build/recovery-interop/synthetic.json',import.meta.url),'utf8'));
assert.equal(fixture.synthetic_only,true);
for (const [field,code] of [['prf',false],['code',true]]) {
  const secret=unb64(fixture[field]);
  try {assert.equal((await openRecovery(fixture,secret,code)).identityValidated,true);}
  finally {secret.fill(0);}
}
await assert.rejects(openRecovery(fixture,new Uint8Array(32)));
await assert.rejects(openRecovery(fixture,new Uint8Array(32),true));
await assert.rejects(openRecovery(fixture,new Uint8Array(31)));
for (const field of ['package_sealed','prf_ark','code_ark','signature']) {
  const changed=structuredClone(fixture), bytes=unb64(changed.record[field]); bytes[bytes.length-1]^=1; changed.record[field]=b64(bytes);
  await assert.rejects(openRecovery(changed,unb64(fixture.prf)));
}
for (const [field,value] of [['account_id','99999999-2222-4333-8444-555555555555'],['generation',2],['credential_id',b64(new Uint8Array(32))]]) {
  const changed=structuredClone(fixture); changed.record[field]=value;
  await assert.rejects(openRecovery(changed,unb64(fixture.prf)));
}
const c={id:'credential',rawId:new Uint8Array([1]),response:{clientDataJSON:new Uint8Array([2]),authenticatorData:new Uint8Array([3]),signature:new Uint8Array([4]),userHandle:null,providerSecret:'SECRET'},getClientExtensionResults:()=>({prf:{results:{first:'SECRET'}}})};
assert.ok(!JSON.stringify(publicCredential(c)).includes('SECRET'));
console.log(JSON.stringify({status:'PASS',checks:13,source:'SYNTHETIC_KOTLIN_IDENTITY_TO_WEBCRYPTO',realProviderSync:'NOT_TESTED'}));
