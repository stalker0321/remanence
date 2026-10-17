export const routes = ['home', 'scan', 'create', 'account', 'settings'] as const

export type RouteName = (typeof routes)[number]

/** A hash names a page. It does not carry a token, and it does not grant a visit. */
export function parseRoute(hash: string): RouteName {
  const raw = hash.replace(/^#/, '').split('?')[0] ?? ''
  const path = raw.replace(/\/+$/, '') || '/'
  switch (path) {
    case '/':
      return 'home'
    case '/scan':
      return 'scan'
    case '/create':
      return 'create'
    case '/account':
      return 'account'
    case '/settings':
      return 'settings'
    default:
      return 'home'
  }
}

export function href(route: RouteName): string {
  if (route === 'home') return '#/'
  return `#/${route}`
}
