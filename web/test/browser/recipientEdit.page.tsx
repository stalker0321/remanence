/** Real Create and recipient/session objects; only directory/camera ports are synthetic. */
import { createRoot } from 'react-dom/client'
import { Create } from '../../src/ui/App'
import { SessionStore } from '../../src/session/session'
import { RecipientDraft } from '../../src/create/recipient'
import { NoteDraft } from '../../src/create/note'
import { PhotoDraft } from '../../src/create/photos'
import { MusicDraft } from '../../src/create/music'
import { translate } from '../../src/i18n/translate'
import type { ApiRequest, ApiResult } from '../../src/auth/http'
import '../../src/design/tokens.css'

const account = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const other = 'bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const bundle = '22222222-2222-4222-8222-222222222222'
const store = new SessionStore()
function signIn(id = account) {
  store.signIn({ accountId: id, bundleId: bundle, handle: 'self_qa', accessToken: 'synthetic-access', refreshToken: 'synthetic-refresh', accessExpiresAt: '2026-10-21T00:00:00Z', refreshExpiresAt: '2026-11-21T00:00:00Z' })
  store.unlock()
}
signIn()
const recipient = new RecipientDraft()
recipient.attach(store)
const requests: { input: ApiRequest; resolve: (result: ApiResult) => void }[] = []
const notices: string[] = []
const rootElement = document.createElement('main')
rootElement.className = 'shell'
document.body.append(rootElement)
const root = createRoot(rootElement)
const note = new NoteDraft()
const photos = new PhotoDraft()
photos.attach(store)
const music = new MusicDraft()
function render() {
  root.render(<Create store={store} recipient={recipient} note={note} photos={photos} music={music}
    t={(key) => translate('en', key)} onNotice={(key) => notices.push(key)}
    camera={{ open: async () => { throw new Error('fixture has no camera') } }}
    transport={{ request: (input) => new Promise((resolve) => requests.push({ input, resolve })) }} />)
}
render()
const fixture = {
  requests: () => requests.map(({ input }) => ({ path: input.path, aborted: input.signal?.aborted ?? false })),
  result(index: number, status = 200) {
    const request = requests[index]!
    const handle = request.input.path.split('/').at(-1)!
    const id = handle === 'self_qa' ? account : other
    request.resolve({ status, body: status !== 200 ? {} : {
      user: { user_id: id, handle },
      key_bundle: { key_bundle_id: bundle, user_id: id, suite: 'HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519',
        protocol_version: 1, encryption_public_keyset: 'CIabc123', signing_public_keyset: 'CJxyz789',
        status: 'ACTIVE', created_at: '2026-10-20T00:00:00Z' },
      directory_version: '9f1c0d2e7a6b4c5d9f1c0d2e7a6b4c5d',
    } })
  },
  state: () => ({ pending: recipient.pending()?.handle ?? null, confirmed: recipient.confirmed()?.handle ?? null, notices }),
  switchAccount: () => signIn(other),
  lock: () => store.lock(),
  unlock: () => store.unlock(),
  render,
  unmount: () => root.unmount(),
}
Object.assign(window, { recipientEdit: fixture })
