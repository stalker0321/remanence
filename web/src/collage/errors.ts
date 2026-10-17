export class CollageError extends Error {
  constructor(readonly code: 'GRANT' | 'INTEGRITY' | 'REJECTED' | 'STALE' | 'UNSUPPORTED') {
    super(code)
    this.name = 'CollageError'
  }
}
