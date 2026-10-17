export const routes = ['home', 'scan', 'create', 'account'] as const

export type RouteName = (typeof routes)[number]

export function parseRoute(hash: string): RouteName {
  const path = hash.replace(/^#/, '').replace(/\/+$/, '') || '/'
  switch (path) {
    case '/':
      return 'home'
    case '/scan':
      return 'scan'
    case '/create':
      return 'create'
    case '/account':
      return 'account'
    default:
      return 'home'
  }
}

export function href(route: RouteName): string {
  if (route === 'home') return '#/'
  return `#/${route}`
}
