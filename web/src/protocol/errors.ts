export class ProtocolError extends Error {
  constructor(readonly code: string) {
    super(code)
    this.name = 'ProtocolError'
  }
}
