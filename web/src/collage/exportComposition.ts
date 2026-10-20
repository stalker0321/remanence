export type ExportOutcome = 'exported' | 'stale' | 'empty' | 'failed'

export interface ExportPorts {
  isCurrent(): boolean
  encode(): Promise<Blob | null>
  createUrl(blob: Blob): string
  download(url: string): void
  revokeUrl(url: string): void
}

/**
 * Encode only the exact composition canvas, then deliver it if this render is
 * still current. Stale encodes and empty/failed encodes never create a URL or
 * start a download; every created URL is revoked on every outcome.
 */
export async function exportComposition(ports: ExportPorts): Promise<ExportOutcome> {
  if (!ports.isCurrent()) return 'stale'
  let blob: Blob | null
  try {
    blob = await ports.encode()
  } catch {
    return 'failed'
  }
  if (blob === null) return 'empty'
  if (!ports.isCurrent()) return 'stale'
  let url: string | null = null
  try {
    url = ports.createUrl(blob)
    if (!ports.isCurrent()) return 'stale'
    ports.download(url)
    return 'exported'
  } catch {
    return 'failed'
  } finally {
    if (url !== null) {
      try {
        ports.revokeUrl(url)
      } catch {
        // A failed revoke must not turn a completed/failed export into stale.
      }
    }
  }
}
