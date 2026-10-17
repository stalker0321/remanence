// ADR-011 browser adapter. No persistent plaintext or external cryptographic JS.
export const VERSION = 'REMANENCE-RECOVERY-V1';
const utf8 = s => new TextEncoder().encode(s);
export const b64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
export function unb64(value) {
  if (typeof value !== 'string' || value.length > 45000 || !/^[A-Za-z0-9_-]+$/.test(value)) throw Error('INVALID_ENCODING');
  const bytes = Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
  if (b64(bytes) !== value) throw Error('INVALID_ENCODING');
  return bytes;
}
const same = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);
const concat = (...arrays) => { const out = new Uint8Array(arrays.reduce((n, a) => n + a.length, 0)); let pos = 0; for (const a of arrays) {out.set(a, pos); pos += a.length;} return out; };
const sha = async bytes => new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
function check(condition) {if (!condition) throw Error('IDENTITY_INTEGRITY_FAILED');}

// Bounded parser for the frozen single-key Tink v1 profile. No arbitrary proto.
function proto(bytes, allowed) {
  const fields = new Map(); let pos = 0;
  function integer() {
    let value = 0, factor = 1;
    for (let i = 0; i < 5; i++) {
      check(pos < bytes.length); const v = bytes[pos++]; value += (v & 127) * factor;
      if (!(v & 128)) {check(value <= 0xffffffff); return value;} factor *= 128;
    }
    throw Error('INVALID_PROTO');
  }
  while (pos < bytes.length) {
    const tag = integer(), field = Math.floor(tag / 8), wire = tag % 8;
    check(allowed.includes(field) && !fields.has(field));
    if (wire === 0) fields.set(field, integer());
    else {check(wire === 2); const n = integer(); check(n <= bytes.length - pos); fields.set(field, bytes.subarray(pos, pos + n)); pos += n;}
  }
  return fields;
}
function keyset(bytes, expectedType, material) {
  const set = proto(bytes, [1, 2]); const key = proto(set.get(2), [1, 2, 3, 4]);
  check(set.get(1) > 0 && set.get(1) === key.get(3) && key.get(2) === 1 && key.get(4) === 1);
  const data = proto(key.get(1), [1, 2, 3]);
  check(new TextDecoder('utf-8', {fatal: true}).decode(data.get(1)) === 'type.googleapis.com/google.crypto.tink.' + expectedType && data.get(3) === material);
  return {id: set.get(1), value: data.get(2)};
}
function edPublic(keysetBytes) {
  const key = keyset(keysetBytes, 'Ed25519PublicKey', 3); const pub = proto(key.value, [1, 2]);
  check((pub.get(1) ?? 0) === 0 && pub.get(2).length === 32);
  return {id: key.id, raw: pub.get(2), value: key.value};
}
function hpkePublic(keysetBytes) {
  const key = keyset(keysetBytes, 'HpkePublicKey', 3); const pub = proto(key.value, [1, 2, 3]); const params = proto(pub.get(2), [1, 2, 3]);
  check((pub.get(1) ?? 0) === 0 && params.get(1) === 1 && params.get(2) === 1 && params.get(3) === 2 && pub.get(3).length === 32);
  return {id: key.id, raw: pub.get(3), value: key.value};
}
async function privateKey(seed, algorithm) {
  check(seed.length === 32);
  const der = concat(new Uint8Array([48,46,2,1,0,48,5,6,3,43,101,algorithm === 'Ed25519' ? 112 : 110,4,34,4,32]), seed);
  try {return await crypto.subtle.importKey('pkcs8', der, algorithm, false, [algorithm === 'Ed25519' ? 'sign' : 'deriveBits']);}
  finally {der.fill(0);}
}
async function validateIdentity(plain, encAnchor, signAnchor) {
  check(plain.length <= 32768 && plain.length >= 12 && new TextDecoder().decode(plain.subarray(0,4)) === 'RPK1');
  const view = new DataView(plain.buffer, plain.byteOffset, plain.length); let pos = 4;
  const take = () => {check(pos + 4 <= plain.length); const n = view.getUint32(pos); pos += 4; check(n > 0 && n <= plain.length - pos); const result = plain.subarray(pos, pos + n); pos += n; return result;};
  const enc = keyset(take(), 'HpkePrivateKey', 2); const sign = keyset(take(), 'Ed25519PrivateKey', 2); check(pos === plain.length);
  const e = proto(enc.value, [1, 2, 3]), s = proto(sign.value, [1, 2, 3]);
  const ep = hpkePublic(encAnchor), sp = edPublic(signAnchor);
  check((e.get(1) ?? 0) === 0 && (s.get(1) ?? 0) === 0 && enc.id === ep.id && sign.id === sp.id && same(e.get(2), ep.value) && same(s.get(3), sp.value));
  // Prove actual private/public correspondence, not only embedded public bytes.
  const signing = await privateKey(s.get(2), 'Ed25519');
  const verifying = await crypto.subtle.importKey('raw', sp.raw, 'Ed25519', false, ['verify']);
  const challenge = crypto.getRandomValues(new Uint8Array(32));
  const sig = await crypto.subtle.sign('Ed25519', signing, challenge);
  check(await crypto.subtle.verify('Ed25519', verifying, sig, challenge));
  const encryption = await privateKey(e.get(3), 'X25519');
  const expected = await crypto.subtle.importKey('raw', ep.raw, 'X25519', false, []);
  const ephemeral = await crypto.subtle.generateKey('X25519', false, ['deriveBits']);
  let left, right;
  try {
    left = new Uint8Array(await crypto.subtle.deriveBits({name:'X25519', public:ephemeral.publicKey}, encryption, 256));
    right = new Uint8Array(await crypto.subtle.deriveBits({name:'X25519', public:expected}, ephemeral.privateKey, 256));
    check(same(left, right));
  } finally {left?.fill(0); right?.fill(0);}
  // Copies survive the package wipe in the caller. They are for the in-memory
  // key owner only; openRecovery still wipes them before returning.
  return {
    signing, encryption,
    signingKeyId: sign.id, encryptionKeyId: enc.id,
    signingSeed: new Uint8Array(s.get(2)), encryptionSeed: new Uint8Array(e.get(3)),
  };
}
export async function openIdentity(reply, secret, useCode = false) {
  check(secret instanceof Uint8Array && secret.length === 32);
  const r = reply.record;
  check(r.version === VERSION && r.generation === 1);
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  check(uuid.test(r.account_id) && uuid.test(r.bundle_id) && unb64(r.salt).length === 32 && unb64(r.credential_id).length <= 1024);
  if (reply.login) check(reply.login.user.user_id === r.account_id && reply.login.active_key_bundle.key_bundle_id === r.bundle_id);
  const ctx = [VERSION, r.account_id, r.bundle_id, 1, r.credential_id, r.salt].join('\n');
  const pack = unb64(r.package_sealed), p = unb64(r.prf_ark), c = unb64(r.code_ark);
  check(pack.length >= 29 && pack.length <= 32796 && p.length === 60 && c.length === 60);
  const anchor = edPublic(unb64(reply.signing_public_keyset)); const signature = unb64(r.signature);
  check(signature.length === 69 && signature[0] === 1 && new DataView(signature.buffer).getUint32(1) === anchor.id);
  const verifier = await crypto.subtle.importKey('raw', anchor.raw, 'Ed25519', false, ['verify']);
  const signed = concat(utf8(ctx + '\nmanifest'), await sha(pack), await sha(p), await sha(c));
  check(await crypto.subtle.verify('Ed25519', verifier, signature.subarray(5), signed));
  const purpose = useCode ? '\ncode-ark' : '\nprf-ark';
  const base = await crypto.subtle.importKey('raw', secret, 'HKDF', false, ['deriveKey']);
  const key = await crypto.subtle.deriveKey({name:'HKDF', hash:'SHA-256', salt:unb64(r.salt), info:utf8(ctx + purpose)}, base, {name:'AES-GCM', length:256}, false, ['decrypt']);
  let ark, plain;
  try {
    const wrapped = useCode ? c : p;
    ark = new Uint8Array(await crypto.subtle.decrypt({name:'AES-GCM', iv:wrapped.subarray(0,12), additionalData:utf8(ctx + purpose)}, key, wrapped.subarray(12)));
    check(ark.length === 32);
    const root = await crypto.subtle.importKey('raw', ark, 'AES-GCM', false, ['decrypt']);
    plain = new Uint8Array(await crypto.subtle.decrypt({name:'AES-GCM', iv:pack.subarray(0,12), additionalData:utf8(ctx + '\npackage')}, root, pack.subarray(12)));
    const opened = await validateIdentity(plain, unb64(reply.encryption_public_keyset), unb64(reply.signing_public_keyset));
    return {accountId: r.account_id, bundleId: r.bundle_id, ...opened};
  } finally {ark?.fill(0); plain?.fill(0);}
}
export async function openRecovery(reply, secret, useCode = false) {
  const opened = await openIdentity(reply, secret, useCode);
  opened.signingSeed.fill(0); opened.encryptionSeed.fill(0);
  return {status:'PASS', identityValidated:true, identityInstalled:false, plaintextBuffersCleared:true};
}
export function publicCredential(c) {
  const r = c.response; const response = {clientDataJSON:b64(r.clientDataJSON)};
  if (r.attestationObject) {response.attestationObject = b64(r.attestationObject); response.transports = r.getTransports?.() ?? [];}
  else {response.authenticatorData=b64(r.authenticatorData); response.signature=b64(r.signature); response.userHandle=r.userHandle ? b64(r.userHandle) : null;}
  return {id:c.id, rawId:b64(c.rawId), type:'public-key', response, clientExtensionResults:{}};
}
export function credentialOptions(raw) {
  const p = structuredClone(raw); p.challenge=unb64(p.challenge);
  if (p.user) p.user.id=unb64(p.user.id);
  for (const field of ['allowCredentials','excludeCredentials']) if (p[field]) p[field]=p[field].map(c => ({...c,id:unb64(c.id)}));
  if (p.extensions?.prf?.eval?.first) p.extensions.prf.eval.first=unb64(p.extensions.prf.eval.first);
  return p;
}
