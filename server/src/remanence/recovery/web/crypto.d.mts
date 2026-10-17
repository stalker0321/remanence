export const VERSION: string

export function b64(bytes: Uint8Array): string
export function unb64(value: string): Uint8Array

export function openIdentity(reply: unknown, secret: Uint8Array, useCode?: boolean): Promise<{
  accountId: string
  bundleId: string
  signing: CryptoKey
  encryption: CryptoKey
  signingKeyId: number
  encryptionKeyId: number
  signingSeed: Uint8Array
  encryptionSeed: Uint8Array
}>

export function openRecovery(reply: unknown, secret: Uint8Array, useCode?: boolean): Promise<{
  status: string
  identityValidated: boolean
  identityInstalled: boolean
  plaintextBuffersCleared: boolean
}>

export function publicCredential(credential: {
  id: string
  rawId: BufferSource
  response: {
    clientDataJSON: BufferSource
    attestationObject?: BufferSource
    getTransports?: () => string[]
    authenticatorData?: BufferSource
    signature?: BufferSource
    userHandle?: BufferSource | null
  }
}): {
  id: string
  rawId: string
  type: 'public-key'
  response: Record<string, unknown>
  clientExtensionResults: Record<string, never>
}

export function credentialOptions(raw: Record<string, unknown>): Record<string, unknown>
