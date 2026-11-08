export const routes = ['home', 'scan', 'create', 'account', 'settings', 'history', 'gift'] as const

export type RouteName = (typeof routes)[number]

/** Who a link addresses a new remanence to; the lookup still runs and the sender still confirms. */
export type LinkedRecipient = { kind: 'self' } | { kind: 'named'; handle: string } | null

export interface AppLocation {
  route: RouteName
  recipient: LinkedRecipient
}

/** Fired after the app changes the URL itself; the browser fires popstate for back and forward. */
export const ROUTE_CHANGE = 'remanence:route'

const PATHS: Record<string, RouteName> = {
  '/': 'home',
  '/scan': 'scan',
  '/create': 'create',
  '/account': 'account',
  '/settings': 'settings',
  '/history': 'history',
  '/c': 'gift',
}
// Handles are 3 to 30 of [a-z0-9_.]; the server keeps app words out of them.
const HANDLE_PATH = /^\/@?([A-Za-z0-9_.]{3,30})$/
// One-segment pages the server renders; reserved as handles too.
const SERVER_PAGES = new Set(['/review', '/legal'])

/**
 * A path names a page and, for /me and /<nick>, who to write to. It does not
 * carry a token, and it does not grant a visit. Old #/ links still resolve.
 */
export function parseLocation(pathname: string, hash = ''): AppLocation {
  const legacy = hash.startsWith('#/') ? hash.slice(1).split('?')[0] ?? '/' : null
  const path = (legacy ?? pathname).replace(/\/+$/, '') || '/'
  const named = PATHS[path]
  if (named) return { route: named, recipient: null }
  if (path === '/gift') return { route: 'gift', recipient: null }
  if (path === '/me') return { route: 'create', recipient: { kind: 'self' } }
  const handle = legacy === null ? HANDLE_PATH.exec(path)?.[1] : undefined
  if (handle && !handle.startsWith('.')) return { route: 'create', recipient: { kind: 'named', handle: handle.toLowerCase() } }
  return { route: 'home', recipient: null }
}

export function parseRoute(pathname: string, hash = ''): RouteName {
  return parseLocation(pathname, hash).route
}

export function currentLocation(): AppLocation {
  return parseLocation(window.location.pathname, window.location.hash)
}

export function currentRoute(): RouteName {
  return currentLocation().route
}

export function href(route: RouteName): string {
  if (route === 'home') return '/'
  if (route === 'gift') return '/c'
  return `/${route}`
}

/** True for a path this document renders itself; anything else is a full page load. */
export function isAppPath(pathname: string): boolean {
  const path = pathname.replace(/\/+$/, '') || '/'
  if (SERVER_PAGES.has(path)) return false
  return path in PATHS || path === '/gift' || path === '/me' || HANDLE_PATH.test(path)
}

export function navigate(path: string, mode: 'push' | 'replace' = 'push'): void {
  if (mode === 'push') history.pushState(null, '', path)
  else history.replaceState(null, '', path)
  window.dispatchEvent(new Event(ROUTE_CHANGE))
}

/** Route listeners hear both the app's own navigations and back/forward. */
export function onRouteChange(listener: () => void, capture = false): () => void {
  window.addEventListener(ROUTE_CHANGE, listener, capture)
  window.addEventListener('popstate', listener, capture)
  return () => {
    window.removeEventListener(ROUTE_CHANGE, listener, capture)
    window.removeEventListener('popstate', listener, capture)
  }
}

/** Same-document links: plain left clicks on app paths change the route without a reload. */
export function interceptLinks(root: Document = document): () => void {
  const onClick = (event: MouseEvent) => {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
    const anchor = (event.target as Element | null)?.closest?.('a[href]') as HTMLAnchorElement | null
    if (!anchor || (anchor.target && anchor.target !== '_self') || anchor.hasAttribute('download')) return
    const url = new URL(anchor.href, window.location.href)
    if (url.origin !== window.location.origin || url.search || url.hash || !isAppPath(url.pathname)) return
    event.preventDefault()
    if (url.pathname === window.location.pathname && !window.location.hash) return
    navigate(url.pathname)
  }
  root.addEventListener('click', onClick)
  return () => root.removeEventListener('click', onClick)
}
