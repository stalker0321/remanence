// Reuses T160 Playwright/CDP tooling. Synthetic test credentials ONLY.
import assert from 'node:assert/strict';
import {createInterface} from 'node:readline';
const lines=createInterface({input:process.stdin})[Symbol.asyncIterator]();
const input=JSON.parse((await lines.next()).value);
const {chromium}=await import(process.env.REMANENCE_PLAYWRIGHT_MODULE);
const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE ? {executablePath:process.env.BROWSER_EXECUTABLE} : {})});
try {
  const context=await browser.newContext(), page=await context.newPage();
  const cdp=await context.newCDPSession(page); await cdp.send('WebAuthn.enable');
  const options={protocol:'ctap2',transport:'usb',hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true,hasPrf:true,defaultBackupEligibility:true,defaultBackupState:true};
  const {authenticatorId}=await cdp.send('WebAuthn.addVirtualAuthenticator',{options});
  const errors=[], requests=[]; page.on('pageerror',e=>errors.push(e.message));
  page.on('request',r=>{if(r.url().includes('/v1/recovery/')) requests.push({path:new URL(r.url()).pathname,body:r.postData() ?? ''});});
  await page.goto(input.url + '/recovery-test/');
  const enrolled=await page.evaluate(async ({token})=>{
    const {publicCredential,credentialOptions,b64}=await import('./crypto.mjs');
    async function api(path,body) {
      const response=await fetch('/v1/recovery/'+path,{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify(body)});
      if(!response.ok) throw Error('SETUP_REJECTED'); return response.json();
    }
    const begin=await api('register/options',{});
    const created=await navigator.credentials.create({publicKey:credentialOptions(begin.options)});
    const registered=await api('register/verify',{flow:begin.flow,response:publicCredential(created)});
    const start=await api('setup/options',{credential_id:registered.credential_id});
    const got=await navigator.credentials.get({publicKey:credentialOptions(start.options)});
    const first=got.getClientExtensionResults().prf?.results?.first;
    if(!first || first.byteLength!==32) throw Error('SYNTHETIC_PRF_MISSING');
    const verified=await api('setup/verify',{flow:start.flow,response:publicCredential(got)});
    const result={flow:verified.flow,credential_id:got.id,prf:b64(first)};
    new Uint8Array(first).fill(0); return result;
  },{token:input.token});
  // Output captured ONLY by the Python synthetic test, never printed as evidence.
  process.stdout.write(JSON.stringify(enrolled)+'\n');
  const continuation=JSON.parse((await lines.next()).value);
  await page.goto(input.url + '/recovery-test/');
  await page.locator('#passkey').click();
  await page.waitForFunction(()=>document.querySelector('#result').textContent.includes('"status"'));
  assert.equal(JSON.parse(await page.locator('#result').textContent()).status,'PASS');
  await page.locator('summary').click();
  await page.locator('#email').fill(input.email); await page.locator('#password').fill(input.password);
  await page.locator('#code').fill(continuation.code); await page.locator('#recover').click();
  await page.waitForFunction(()=>document.querySelector('#result').textContent.includes('"status"'));
  assert.equal(JSON.parse(await page.locator('#result').textContent()).status,'PASS');
  assert.equal(await page.locator('#code').inputValue(),''); assert.equal(await page.locator('#password').inputValue(),'');
  const {credentials}=await cdp.send('WebAuthn.getCredentials',{authenticatorId});
  await cdp.send('WebAuthn.removeVirtualAuthenticator',{authenticatorId});
  const unsupported=await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{...options,hasPrf:false}});
  await cdp.send('WebAuthn.addCredential',{authenticatorId:unsupported.authenticatorId,credential:credentials[0]});
  const before=requests.filter(r=>r.path.endsWith('/login/verify')).length;
  await page.locator('#passkey').click();
  await page.waitForFunction(()=>document.querySelector('#result').textContent.includes('PRF_UNAVAILABLE'));
  assert.equal(requests.filter(r=>r.path.endsWith('/login/verify')).length,before);
  assert.deepEqual(errors,[]);
  for(const request of requests) assert.ok(!/"(prf|plaintext|first|recovery_code)"/.test(request.body));
  process.stdout.write(JSON.stringify({status:'PASS',evidenceClass:'CHROMIUM_VIRTUAL_AUTHENTICATOR_REAL_PRODUCT_API',passkeyIdentityOpen:true,codeIdentityOpen:true,missingPrfNoLoginSession:true,secretTransportFieldsAbsent:true,realProviderSync:'NOT_TESTED'})+'\n');
} finally {await browser.close();}
