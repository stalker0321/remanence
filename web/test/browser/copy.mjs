import { readFile } from 'node:fs/promises'
import { join } from 'node:path'

// The site's own copy, by key. A proof that waits for a line of text names the
// line by its catalog key, so rewording the copy does not turn the proof stale.
export async function copy(web, language = 'en') {
  return JSON.parse(await readFile(join(web, 'src', 'i18n', 'locales', `${language}.json`), 'utf8'))
}
