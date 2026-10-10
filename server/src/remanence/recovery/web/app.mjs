import {unb64, publicCredential, credentialOptions, openRecovery} from './crypto.mjs';
const root = new URL('../', location.href);
const result = document.querySelector('#result');
async function api(path, body, token) {
  const response = await fetch(new URL(path, root), {method:body === undefined ? 'GET' : 'POST', credentials:'omit', redirect:'error', cache:'no-store',
    headers:{'Content-Type':'application/json', ...(token ? {Authorization:'Bearer ' + token} : {})}, ...(body === undefined ? {} : {body:JSON.stringify(body)})});
  if (!response.ok) throw Error('REQUEST_REJECTED');
  return response.json();
}
async function act(fn) {
  const buttons=[...document.querySelectorAll('button')]; if (buttons.some(b => b.disabled)) return;
  buttons.forEach(b => b.disabled=true); result.textContent='RUNNING';
  try {result.textContent=JSON.stringify(await fn(),null,2);}
  catch (e) {result.textContent=JSON.stringify({status:'FAIL',reason:e.message === 'PRF_UNAVAILABLE' ? 'PRF_UNAVAILABLE' : 'RECOVERY_NOT_COMPLETED'},null,2);}
  finally {buttons.forEach(b => b.disabled=false);}
}
document.querySelector('#passkey').onclick=() => act(async () => {
  const begin=await api('v1/recovery/login/options',{});
  const credential=await navigator.credentials.get({publicKey:credentialOptions(begin.options)});
  const first=credential.getClientExtensionResults()?.prf?.results?.first;
  if (!first || first.byteLength !== 32) throw Error('PRF_UNAVAILABLE');
  const secret=new Uint8Array(first); let out;
  try {
    out=await api('v1/recovery/login/verify',{flow:begin.flow,response:publicCredential(credential)});
    if (out.record.credential_id !== credential.id) throw Error('WRONG_CREDENTIAL');
    return {...await openRecovery(out,secret), authenticationVerified:true, client:'WEB', evidenceClass:'REAL_ACCOUNT_IDENTITY_OPEN'};
  } finally {
    secret.fill(0);
    // This read-only test does not retain a product session, even after PASS.
    if (out?.login) await api('v1/auth/logout',{},out.login.access_token).catch(() => {});
  }
});
document.querySelector('#recover').onclick=() => act(async () => {
  const email=document.querySelector('#email'), password=document.querySelector('#password'), code=document.querySelector('#code');
  let secret, login;
  try {
    secret=unb64(code.value); code.value='';
    if (secret.length !== 32) throw Error('INVALID_CODE');
    login=await api('v1/auth/login',{email:email.value,password:password.value}); password.value='';
    const out=await api('v1/recovery/record',undefined,login.access_token);
    if (out.record.account_id !== login.user.user_id || out.record.bundle_id !== login.active_key_bundle.key_bundle_id) throw Error('WRONG_ACCOUNT');
    return {...await openRecovery(out,secret,true), authenticationVerified:true, client:'WEB', evidenceClass:'REAL_ACCOUNT_IDENTITY_OPEN'};
  } finally {secret?.fill(0); password.value=''; code.value=''; if (login) await api('v1/auth/logout',{},login.access_token).catch(() => {});}
});
