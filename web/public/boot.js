window.__remanenceBoot = { errors: [], resources: [] }
window.addEventListener('error', (event) => {
  const target = event.target
  if (target && target !== window) {
    const source = target.src || target.href || ''
    window.__remanenceBoot.resources.push(String(source))
    return
  }
  window.__remanenceBoot.errors.push(String(event.message || 'error'))
}, true)
window.addEventListener('unhandledrejection', (event) => {
  window.__remanenceBoot.errors.push(String(event.reason))
})
