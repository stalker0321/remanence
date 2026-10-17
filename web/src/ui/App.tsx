import { useEffect, useState, useSyncExternalStore } from 'react'
import { languages, type Language } from '../i18n/catalog'
import { phaseKey, translate } from '../i18n/translate'
import { writeLanguage, type PreferencePort } from '../preferences/language'
import { href, parseRoute, type RouteName } from '../router/route'
import { SessionStore } from '../session/session'

export function App({ store, preferences }: { store: SessionStore; preferences: PreferencePort }) {
  const view = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const [route, setRoute] = useState<RouteName>(() => parseRoute(window.location.hash))
  const t = (key: Parameters<typeof translate>[1]) => translate(view.language, key)

  useEffect(() => {
    const onHash = () => setRoute(parseRoute(window.location.hash))
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  function chooseLanguage(language: Language) {
    writeLanguage(preferences, language)
    store.setLanguage(language)
    document.documentElement.lang = language
  }

  return (
    <main className="shell">
      <div className="bar">
        <p className="mark">remanence</p>
        <nav aria-label={t('language')}>
          {languages.map((language) => (
            <button
              key={language}
              type="button"
              className="quiet"
              aria-pressed={view.language === language}
              onClick={() => chooseLanguage(language)}
            >
              {language}
            </button>
          ))}
        </nav>
      </div>
      {route === 'home' ? <Home t={t} /> : null}
      {route === 'scan' ? <Waiting t={t} title="scanTitle" body="scanWaiting" /> : null}
      {route === 'create' ? <Waiting t={t} title="homeMakeTitle" body="makeWaiting" /> : null}
      {route === 'account' ? <Account store={store} t={t} /> : null}
      <p className="status" role="status">{t(phaseKey(view.phase))}</p>
      <p className="status" role="alert">{t('errorRegion')}</p>
    </main>
  )
}

function Home({ t }: { t: (key: Parameters<typeof translate>[1]) => string }) {
  return (
    <>
      <section className="card">
        <h1>{t('homeOpenTitle')}</h1>
        <p>{t('homeOpenBody')}</p>
        <div className="actions">
          <a className="button primary" href={href('scan')}>{t('scan')}</a>
        </div>
      </section>
      <section className="card">
        <h2>{t('homeMakeTitle')}</h2>
        <p>{t('homeMakeBody')}</p>
        <div className="actions">
          <a className="button" href={href('create')}>{t('make')}</a>
        </div>
      </section>
      <div className="actions">
        <a href={href('account')}>{t('account')}</a>
      </div>
    </>
  )
}

function Waiting({
  t,
  title,
  body,
}: {
  t: (key: Parameters<typeof translate>[1]) => string
  title: Parameters<typeof translate>[1]
  body: Parameters<typeof translate>[1]
}) {
  return (
    <section className="card">
      <h1>{t(title)}</h1>
      <p>{t(body)}</p>
      <div className="actions">
        <a href={href('home')}>{t('back')}</a>
      </div>
    </section>
  )
}

function Account({
  store,
  t,
}: {
  store: SessionStore
  t: (key: Parameters<typeof translate>[1]) => string
}) {
  const view = useSyncExternalStore(store.subscribe, store.getSnapshot)
  const signedIn = view.phase !== 'signed-out'
  return (
    <section className="card">
      <h1>{t('authTitle')}</h1>
      <p>{t('authBody')}</p>
      <p>{view.handle ? view.handle : t(phaseKey(view.phase))}</p>
      <div className="actions">
        {signedIn ? (
          <>
            <button type="button" onClick={() => store.lock()}>{t('lock')}</button>
            <button type="button" onClick={() => store.signOut()}>{t('signOut')}</button>
          </>
        ) : null}
        <a href={href('home')}>{t('back')}</a>
      </div>
    </section>
  )
}
