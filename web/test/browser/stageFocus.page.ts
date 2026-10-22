/** Isolated DOM-selector/focus contract. Does NOT mount a sheet, create a grant,
 * admit a capsule, invoke a provider, or exercise a protected harness. */
import { stageFocusFallback } from '../../src/collage/stage'

const results: { name: string; ok: boolean }[] = []
function run(name: string, markup: string, expected: string | null) {
  document.body.innerHTML = markup
  const target = stageFocusFallback(document)
  const match = expected === null ? target === null : target === document.getElementById(expected)
  target?.focus({ preventScroll: true })
  results.push({ name, ok: match && (target === null || document.activeElement === target) })
}
run('new div shell returns enabled route card control', '<div class="shell" tabindex="-1"><main class="route-content"><section class="card"><button disabled>busy</button><button id="action">continue</button><a href="#" id="later">back</a></section></main></div>', 'action')
run('new shell skips disabled control for route link', '<div class="shell" tabindex="-1"><main class="route-content"><section class="card"><button disabled>busy</button><a href="#" id="route-link">back</a></section></main></div>', 'route-link')
run('new shell without a card receives fallback focus', '<div class="shell" id="shell" tabindex="-1"><main class="route-content"><h1>home</h1></main></div>', 'shell')
run('legacy main shell remains compatible', '<main class="shell" tabindex="-1"><section class="card"><button id="legacy">continue</button></section></main>', 'legacy')
run('removed shell has no fallback', '<div>outside the app</div>', null)
Object.assign(window, { stageFocusResults: results })
