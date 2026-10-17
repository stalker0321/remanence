import { concat, utf8 } from '../src/protocol/bytes'
import { protoBytes, protoVarint } from '../src/protocol/proto'

const status: Record<string, number> = { ENABLED: 1, DISABLED: 2, DESTROYED: 3 }
const prefix: Record<string, number> = { TINK: 1, LEGACY: 2, RAW: 3, CRUNCHY: 4 }
const material: Record<string, number> = { SYMMETRIC: 1, ASYMMETRIC_PRIVATE: 2, ASYMMETRIC_PUBLIC: 3 }

export interface JsonKeyset {
  primaryKeyId: number
  key: Array<{
    keyData: { typeUrl: string; value: string; keyMaterialType: string }
    status: string
    keyId: number
    outputPrefixType: string
  }>
}

/** Test-only conversion of a Tink JSON keyset into the binary wire keyset. */
export function keysetFromJson(json: JsonKeyset): Uint8Array {
  if (json.key.length !== 1) throw new Error('fixture keyset is not single-key')
  const key = json.key[0]
  const data = concat([
    protoBytes(1, utf8(key.keyData.typeUrl)),
    protoBytes(2, standardBase64(key.keyData.value)),
    protoVarint(3, material[key.keyData.keyMaterialType]),
  ])
  const message = concat([
    protoBytes(1, data),
    protoVarint(2, status[key.status]),
    protoVarint(3, key.keyId),
    protoVarint(4, prefix[key.outputPrefixType]),
  ])
  return concat([protoVarint(1, json.primaryKeyId), protoBytes(2, message)])
}

function standardBase64(value: string): Uint8Array {
  const binary = atob(value)
  const out = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i)
  return out
}
