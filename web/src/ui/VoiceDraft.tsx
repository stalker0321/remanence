import { useEffect, useRef, useState } from 'react'
import type { MessageKey } from '../i18n/catalog'
import { voiceLength } from '../collage/songBand'
import { VOICE_MAX_MS } from '../collage/wire'
import { measureVoice, VoiceError, VoiceRecorder, voiceRecordingSupported, type VoiceFailure, type VoiceRecording } from '../create/voice'

const FAILURE: Record<VoiceFailure, MessageKey> = {
  unsupported: 'voiceUnsupported',
  denied: 'voiceDenied',
  failed: 'voiceFailed',
  'too-large': 'voiceTooLarge',
}

/**
 * The voice tab of the sound section (ADR-021, the owner's look of 23
 * November): tap to record, tap to stop, at most a minute; then listen,
 * record again or delete. The recording replaces the song.
 */
export function VoiceDraft(props: {
  voice: VoiceRecording | null
  onVoice: (voice: VoiceRecording | null) => void
  songChosen: boolean
  disabled: boolean
  t: (key: MessageKey) => string
}) {
  const { voice, onVoice, t } = props
  const [phase, setPhase] = useState<'idle' | 'recording' | 'measuring'>('idle')
  const [failure, setFailure] = useState<VoiceFailure | null>(null)
  const [elapsed, setElapsed] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [url, setUrl] = useState<string | null>(null)
  const recorder = useRef<VoiceRecorder | null>(null)
  const audio = useRef<HTMLAudioElement | null>(null)
  const supported = voiceRecordingSupported()

  // The recording plays from an object URL that lives as long as it does.
  useEffect(() => {
    if (voice === null) {
      setUrl(null)
      return
    }
    const next = URL.createObjectURL(new Blob([voice.bytes as unknown as BlobPart], { type: 'audio/mp4' }))
    setUrl(next)
    setPlaying(false)
    return () => URL.revokeObjectURL(next)
  }, [voice])

  useEffect(() => {
    if (phase !== 'recording') return
    const started = Date.now()
    const tick = setInterval(() => setElapsed(Math.min(VOICE_MAX_MS, Date.now() - started)), 250)
    return () => clearInterval(tick)
  }, [phase])

  // Leaving the page drops a recording in progress and frees the microphone.
  useEffect(() => () => recorder.current?.cancel(), [])

  async function record(): Promise<void> {
    setFailure(null)
    audio.current?.pause()
    const next = new VoiceRecorder()
    recorder.current = next
    setElapsed(0)
    try {
      await next.start(() => { void stop() })
      setPhase('recording')
    } catch (error) {
      recorder.current = null
      setFailure(error instanceof VoiceError ? error.code : 'failed')
    }
  }

  async function stop(): Promise<void> {
    const current = recorder.current
    if (current === null) return
    recorder.current = null
    setPhase('measuring')
    try {
      onVoice(await measureVoice(await current.stop()))
    } catch (error) {
      setFailure(error instanceof VoiceError ? error.code : 'failed')
    } finally {
      setPhase('idle')
    }
  }

  function toggle(): void {
    const element = audio.current
    if (element === null) return
    if (element.paused) void element.play().catch(() => setPlaying(false))
    else element.pause()
  }

  if (!supported) return <p className="field-hint">{t('voiceUnsupported')}</p>

  return (
    <div className="voice-draft">
      {props.songChosen && voice === null ? <p className="field-hint">{t('voiceReplacesSong')}</p> : null}
      {phase === 'recording' ? (
        <div className="voice-row">
          <button type="button" className="primary voice-record is-recording" onClick={() => { void stop() }}>
            <span className="voice-dot" aria-hidden="true" />{t('voiceStop')}
          </button>
          <span className="voice-time" role="timer">{voiceLength(Math.max(1000, elapsed))} / {voiceLength(VOICE_MAX_MS)}</span>
        </div>
      ) : voice !== null ? (
        <div className="voice-row">
          <button type="button" className="primary" aria-pressed={playing} disabled={url === null} onClick={toggle}>
            {t(playing ? 'voicePauseOwn' : 'voiceListenOwn')}
          </button>
          <span className="voice-time">{voiceLength(voice.durationMs)}</span>
          <button type="button" disabled={props.disabled || phase !== 'idle'} onClick={() => { void record() }}>{t('voiceRerecord')}</button>
          <button type="button" className="quiet" disabled={props.disabled || phase !== 'idle'} onClick={() => onVoice(null)}>{t('voiceDelete')}</button>
          {url !== null ? (
            <audio
              ref={audio}
              src={url}
              preload="auto"
              hidden
              onPlay={() => setPlaying(true)}
              onPause={() => setPlaying(false)}
              onEnded={(event) => { event.currentTarget.currentTime = 0; setPlaying(false) }}
            />
          ) : null}
        </div>
      ) : (
        <div className="voice-row">
          <button type="button" className="primary voice-record" disabled={props.disabled || phase !== 'idle'} onClick={() => { void record() }}>
            <span className="voice-dot" aria-hidden="true" />{t('voiceRecord')}
          </button>
        </div>
      )}
      {failure !== null ? <p role="alert">{t(FAILURE[failure])}</p> : <p className="field-hint">{t('voiceHint')}</p>}
    </div>
  )
}
