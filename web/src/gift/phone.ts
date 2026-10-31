/** Countries the first gift reaches (matches the server's SMS allowlist). */
export const DIAL_CODES: Record<string, string> = {
  AT: '43', BE: '32', BG: '359', CH: '41', CY: '357', CZ: '420', DE: '49', DK: '45', EE: '372', ES: '34',
  FI: '358', FR: '33', GB: '44', GR: '30', HR: '385', HU: '36', IE: '353', IS: '354', IT: '39', LI: '423',
  LT: '370', LU: '352', LV: '371', MT: '356', NL: '31', NO: '47', PL: '48', PT: '351', RO: '40', SE: '46',
  SI: '386', SK: '421', UA: '380',
}

const E164 = /^\+[1-9][0-9]{7,14}$/

/** Local numbers become E.164. Null when it cannot be a number. Only Italy keeps its leading zero. */
export function toE164(typed: string, country: string): string | null {
  const compact = typed.replace(/[\s().-]/g, '')
  if (compact.startsWith('+')) return E164.test(compact) ? compact : null
  if (compact.startsWith('00')) return toE164(`+${compact.slice(2)}`, country)
  const dial = DIAL_CODES[country]
  if (!dial || !/^[0-9]+$/.test(compact)) return null
  const national = country === 'IT' ? compact : compact.replace(/^0+/, '')
  const number = `+${dial}${national}`
  return E164.test(number) ? number : null
}

export function defaultCountry(language: string): string {
  const region = /-([A-Za-z]{2})$/.exec(language)?.[1]?.toUpperCase()
  if (region && DIAL_CODES[region]) return region
  return language.toLowerCase().startsWith('uk') ? 'UA' : 'NL'
}
