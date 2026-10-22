// Actual React geometry regression. Usage: node ... <webDir> <newProofDir> [baseReport.json]
// A base run intentionally exits 1 with F1/F2/F3 violations; corrected source must exit 0.
import assert from 'node:assert/strict'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, join } from 'node:path'
import { createHash } from 'node:crypto'
import { fixtureHost } from './fixtureHost.mjs'

const [webArg, proofArg, baselineArg] = process.argv.slice(2)
assert.ok(webArg && proofArg, 'webDir and fresh proofDir required')
const web = resolve(webArg), proof = resolve(proofArg)
await mkdir(proof, { recursive: false })
await mkdir(join(proof, 'images'))
const report = { target: process.env.T406_TARGET || 'unspecified', samples: [], checks: [], violations: [],
  images: [], errors: [], external: [], portCalls: [], completed: false }
const baseline = baselineArg ? JSON.parse(await readFile(baselineArg, 'utf8')) : null
const host = await fixtureHost(web, proof, 'responsiveLayout.page.tsx')
report.browserSha256 = host.executableSha256
const context = await host.browser.newContext({ viewport: { width: 320, height: 900 } })
await context.route('**/*', route => {
  const url = new URL(route.request().url())
  if (url.protocol === 'blob:' || url.protocol === 'data:' ||
      (url.origin === new URL(host.url).origin && /^\/(?:assets\/[\w.-]+|fonts\/[\w.-]+)?$/.test(url.pathname))) return route.continue()
  report.external.push(url.href); return route.abort()
})
const page = await context.newPage()
page.setDefaultTimeout(15000)
page.on('pageerror', error => report.errors.push(String(error)))
const languages = ['en', 'ru', 'uk'], widths = [320, 390, 1280]
let active = ''
const check = (family, name, ok, detail = null) => {
  const item = { state: active, family, name, ok, detail }
  report.checks.push(item); if (!ok) report.violations.push(item)
}
const paint = () => page.waitForFunction(() => document.querySelector('[data-testid=creator-canvas]')?.getAttribute('data-painted') === 'ready')
async function load(stage, lang, width, scale) {
  active = `${stage}-${lang}-${width}-${scale}`
  await page.setViewportSize({ width, height: width === 390 ? 844 : 900 })
  await page.goto(`${host.url}?stage=${stage}&lang=${lang}&scale=${scale}`)
  await page.waitForFunction(() => window.layoutFixture && document.querySelector('.shell main'))
  await page.evaluate(() => document.fonts.ready)
  if (stage === 'design') { await page.evaluate(() => window.layoutFixture.pick()); await paint(); await page.getByTestId('creator-slot-1').click() }
  if (stage === 'settings') await page.waitForSelector('.send-access')
  if (stage === 'history') await page.waitForFunction(() => document.querySelectorAll('.history-list>li').length === 2)
  await page.evaluate(() => new Promise(ok => requestAnimationFrame(() => requestAnimationFrame(ok))))
  await page.evaluate(() => window.scrollTo(0, 0))
}
async function geometry() {
  return page.evaluate(() => {
    const box = element => {
      const r = element.getBoundingClientRect(), s = getComputedStyle(element)
      const range = document.createRange(); range.selectNodeContents(element)
      const textRects = [...range.getClientRects()].map(r => ({ x:r.x, right:r.right, width:r.width, y:r.y, bottom:r.bottom }))
      return { text:element.textContent, x:r.x, right:r.right, y:r.y, bottom:r.bottom, width:r.width, height:r.height,
        clientWidth:element.clientWidth, scrollWidth:element.scrollWidth, fontSize:s.fontSize, fontFamily:s.fontFamily,
        background:s.backgroundColor, color:s.color, outline:s.outline, overflowX:s.overflowX,
        selected:element.getAttribute('aria-selected') || element.getAttribute('aria-pressed'), textRects }
    }
    const bar = document.querySelector('.bar'), pager = document.querySelector('.creator-pager')
    const canvas = document.querySelector('[data-testid=creator-canvas]')
    const list = document.querySelector('.language-list')
    return { rootFont:getComputedStyle(document.documentElement).fontSize, viewport:[innerWidth,innerHeight], scrollX,scrollY,
      documentWidth:document.documentElement.scrollWidth, bodyOverflow:getComputedStyle(document.body).overflowX,
      bar:box(bar), headerChildren:[...bar.children].map(box), trigger:box(bar.querySelector('button')),
      headings:[...document.querySelectorAll('.card h1,.card h2')].map(box),
      pager:pager ? { box:box(pager), children:[...pager.children].map(box) } : null,
      canvas:canvas ? { box:box(canvas), width:canvas.width,height:canvas.height,png:canvas.toDataURL() } : null,
      list:list ? { box:box(list), parent:box(list.closest('.bar') || list.closest('.settings-group').querySelector('.language-menu')),
        options:[...list.querySelectorAll('[role=option]')].map(box) } : null,
      phase:window.layoutFixture.state().phase, language:window.layoutFixture.state().language }
  })
}
function fits(child, parent) { return child.x >= parent.x - 1 && child.right <= parent.right + 1 }
function contentFits(item) {
  return item.scrollWidth <= item.clientWidth + 1 && item.textRects.every(r => r.x >= item.x - 1 && r.right <= item.right + 1)
}
function validate(g, scale, stage) {
  check('document', 'no horizontal document scrolling', g.documentWidth <= g.viewport[0] + 1, g.documentWidth)
  check('inverse', 'original root text size retained', Math.abs(parseFloat(g.rootFont) - 18 * scale) < .01, g.rootFont)
  check('inverse', 'body does not hide overflow', !['hidden','clip'].includes(g.bodyOverflow), g.bodyOverflow)
  check('F1', 'brand and complete trigger inside local header', g.headerChildren.every(c => fits(c, g.bar)), g.headerChildren)
  for (const h of g.headings) check('F3', 'complete heading fits its own content box', contentFits(h), h)
  if (g.pager) {
    check('F2', 'pager local scroll width fits', contentFits(g.pager.box), g.pager.box)
    check('F2', 'label and both whole buttons inside pager', g.pager.children.every(c => fits(c, g.pager.box) && contentFits(c)), g.pager.children)
    for (const c of [g.pager.children[0],g.pager.children[2]]) check('inverse', 'pager touch targets retained', c.width >= 44 && c.height >= 44, [c.width,c.height])
    check('inverse', 'canvas dimensions preserved', g.canvas.width === 360 && g.canvas.height === 640, [g.canvas.width,g.canvas.height])
  }
  if (g.list) {
    check('F1', 'popover within full local anchor width', fits(g.list.box, g.list.parent), g.list)
    check('F1', 'popover within viewport', g.list.box.x >= -1 && g.list.box.right <= g.viewport[0]+1 &&
      g.list.box.y >= -1 && g.list.box.bottom <= g.viewport[1]+1, g.list.box)
    check('F1', 'full option text and targets inside popover', g.list.options.every(c => fits(c,g.list.box) && contentFits(c)), g.list.options)
    check('focus', 'one visible selected option', g.list.options.filter(o => o.selected === 'true').length === 1)
  }
  if (baseline && !g.list) {
    const old = baseline.samples.find(s => s.state === active && !s.menu)
    assert.ok(old, `baseline sample ${active}`)
    if (stage === 'design') {
      check('inverse', 'same preview pixels as base', g.canvas.png === old.geometry.canvas.png)
      check('inverse', 'same preview display size as base', Math.abs(g.canvas.box.width-old.geometry.canvas.box.width)<1 && Math.abs(g.canvas.box.height-old.geometry.canvas.box.height)<1,
        [old.geometry.canvas.box.width,g.canvas.box.width,old.geometry.canvas.box.height,g.canvas.box.height])
    }
    if (g.viewport[0] >= 390 && scale === 1) {
      const before=old.geometry
      check('inverse', 'normal wide header size/style unchanged', ['width','height','fontSize','background','color'].every(k => g.trigger[k] === before.trigger[k]))
      check('inverse', 'normal wide heading sizes unchanged', g.headings.every((h,i) => h.fontSize===before.headings[i].fontSize && Math.abs(h.height-before.headings[i].height)<1))
      if (g.pager) check('inverse', 'normal wide pager remains single row', Math.abs(g.pager.children[0].y-g.pager.children[2].y)<1)
    }
  }
}
async function capture(name, anchor) {
  if (anchor) await page.locator(anchor).first().evaluate(e => window.scrollTo(0,e.getBoundingClientRect().top+scrollY-16))
  const path = join(proof,'images',name+'.png')
  await page.screenshot({path})
  report.images.push({name:name+'.png',state:active,geometry:await geometry(),sha256:createHash('sha256').update(await readFile(path)).digest('hex')})
}
const shots = new Set(['signedout-en-320-1.5','signedout-ru-1280-1','design-en-320-1.5','design-ru-320-1.5',
  'design-uk-390-1','design-en-1280-1','settings-uk-320-1.5','settings-uk-1280-1','history-uk-320-1.5'])
try {
  const cases=[]
  for(const stage of ['signedout','design','settings','history'])for(const lang of languages)for(const width of widths)for(const scale of [1,1.5])cases.push([stage,lang,width,scale])
  for(const stage of ['signedout','settings'])for(const lang of languages)for(const width of widths)cases.push([stage,lang,width,1.8])
  for(const [stage,lang,width,scale] of cases) {
    await load(stage,lang,width,scale)
    const g=await geometry();validate(g,scale,stage);report.samples.push({state:active,menu:false,geometry:g})
    if(shots.has(active))await capture(active,stage==='design'?'.creator-composer':null)
    if(stage==='signedout'||stage==='settings') {
      const scope=stage==='settings'?'.settings-group':'.bar'
      const trigger=page.locator(`${scope} .language-trigger`)
      await trigger.focus();await page.keyboard.press('ArrowDown');await page.getByRole('listbox').waitFor()
      await page.locator(`${scope} .language-menu`).evaluate(e=>window.scrollTo(0,e.getBoundingClientRect().top+scrollY-16))
      const menu=await geometry();validate(menu,scale,stage);report.samples.push({state:active,menu:true,geometry:menu})
      check('focus','selected option focused',await page.locator('[aria-selected=true]').evaluate(e=>e===document.activeElement))
      check('focus','selection does not change session phase',menu.phase===g.phase)
      if((width===320 && scale===1.8 && lang==='ru') || (width===1280&&scale===1&&lang==='en'))await capture(active+'-menu')
      await page.keyboard.press('Escape');check('focus','Escape returns trigger focus',await trigger.evaluate(e=>e===document.activeElement))
      check('focus','Escape closes menu',await page.getByRole('listbox').count()===0)
      if(stage==='signedout'&&lang==='uk'&&width===320&&scale===1.8) {
        await page.keyboard.press('ArrowDown');await page.keyboard.press('ArrowUp');await page.keyboard.press('Enter')
        check('focus','UK arrow selection changes to RU',await page.evaluate(()=>window.layoutFixture.state().language)==='ru')
        await page.keyboard.press('ArrowDown');await capture('exact-T402-ru-320-1.8-menu');await page.keyboard.press('Escape')
      }
    }
    if(stage==='design') {
      const count=await page.getByTestId('creator-count').textContent()
      check('interaction','first previous disabled',await page.getByTestId('creator-prev').isDisabled())
      await page.getByTestId('creator-next').focus();await page.keyboard.press('Enter');await paint()
      check('interaction','keyboard next changes candidate',await page.getByTestId('creator-count').textContent()!==count)
      await page.getByTestId('creator-prev').click();await paint()
      check('interaction','previous returns candidate',await page.getByTestId('creator-count').textContent()===count)
      await page.getByTestId('creator-slot-0').focus();await page.keyboard.press('Tab')
      check('focus','Tab reaches next slot',await page.getByTestId('creator-slot-1').evaluate(e=>e===document.activeElement))
      const value=await page.getByTestId('creator-transform-readout').textContent()
      await page.getByTestId('creator-zoom-in').click();await page.getByTestId('creator-reset').click()
      check('interaction','crop reset readout unchanged',await page.getByTestId('creator-transform-readout').textContent()===value)
    }
    const state=await page.evaluate(()=>window.layoutFixture.state());report.portCalls.push({state:active,calls:state.calls})
    assert.deepEqual(state.forbidden,[])
  }
  await page.emulateMedia({reducedMotion:'reduce'})
  await load('signedout','en',390,1)
  await page.locator('.bar .language-trigger').focus();await page.keyboard.press('ArrowDown');await page.keyboard.press('Escape')
  check('focus','reduced-motion Escape returns trigger',await page.locator('.bar .language-trigger').evaluate(e=>e===document.activeElement))
  report.completed=true
} catch(error) {
  report.errors.push({state:active,error:String(error),stack:error.stack})
  await page.screenshot({path:join(proof,'failure.png'),fullPage:true}).catch(()=>{})
} finally {
  report.summary={states:report.samples.filter(s=>!s.menu).length,menus:report.samples.filter(s=>s.menu).length,
    checks:report.checks.length,violations:report.violations.length,images:report.images.length,
    families:[...new Set(report.violations.map(v=>v.family))],errors:report.errors.length,external:report.external.length}
  await writeFile(join(proof,'report.json'),JSON.stringify(report,null,2)+'\n')
  await context.close();await host.close()
}
console.log(JSON.stringify({completed:report.completed,...report.summary}))
if(!report.completed||report.violations.length||report.errors.length||report.external.length)process.exitCode=1
