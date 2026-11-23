import { useEffect, useRef, useState } from 'react'
import type { MessageKey } from '../i18n/catalog'
import { voiceLength } from '../collage/songBand'
import { VIDEO_MAX_MS } from '../collage/wire'
import { measureVideo, VideoError, VideoRecorder, videoRecordingSupported, type VideoFailure, type VideoRecording } from '../create/videoSquare'

const FAILURE: Record<VideoFailure, MessageKey> = {
  unsupported: 'videoUnsupported',
  denied: 'videoDenied',
  failed: 'videoFailed',
  'too-large': 'videoTooLarge',
  'too-short': 'videoTooShort',
}

/**
 * The video square in the photo step (ADR-021, the owner's look of 23
 * November): it takes one photo's place. "Record a video" opens the front
 * camera in a square viewfinder; tap to start, tap to stop, at most 30 s.
 * Then it loops silently like on the card, with record again and delete.
 */
export function VideoDraft(props: {
  video: VideoRecording | null
  onVideo: (video: VideoRecording | null) => void
  photosFull: boolean
  disabled: boolean
  t: (key: MessageKey) => string
}) {
  const { video, onVideo, t } = props
  const [phase, setPhase] = useState<'idle' | 'camera' | 'recording' | 'measuring'>('idle')
  const [failure, setFailure] = useState<VideoFailure | null>(null)
  const [elapsed, setElapsed] = useState(0)
  const [url, setUrl] = useState<string | null>(null)
  const recorder = useRef<VideoRecorder | null>(null)
  const viewfinder = useRef<HTMLVideoElement | null>(null)
  const supported = videoRecordingSupported()

  useEffect(() => {
    if (video === null) {
      setUrl(null)
      return
    }
    const next = URL.createObjectURL(new Blob([video.bytes as unknown as BlobPart], { type: 'video/mp4' }))
    setUrl(next)
    return () => URL.revokeObjectURL(next)
  }, [video])

  useEffect(() => {
    if (phase !== 'recording') return
    const started = Date.now()
    const tick = setInterval(() => setElapsed(Math.min(VIDEO_MAX_MS, Date.now() - started)), 250)
    return () => clearInterval(tick)
  }, [phase])

  // Leaving the page drops a take in progress and frees the camera.
  useEffect(() => () => recorder.current?.close(), [])

  useEffect(() => {
    if (phase !== 'camera' || recorder.current !== null) return
    const element = viewfinder.current
    if (element === null) return
    const next = new VideoRecorder()
    recorder.current = next
    next.start(element).catch((error: unknown) => {
      if (recorder.current !== next) return
      recorder.current = null
      next.close()
      setFailure(error instanceof VideoError ? error.code : 'failed')
      setPhase('idle')
    })
  }, [phase])

  function open(): void {
    setFailure(null)
    setElapsed(0)
    setPhase('camera')
  }

  function close(): void {
    recorder.current?.close()
    recorder.current = null
    setPhase('idle')
  }

  function record(): void {
    const current = recorder.current
    if (current === null) return
    try {
      current.record(() => { void stop() })
      setPhase('recording')
    } catch (error) {
      close()
      setFailure(error instanceof VideoError ? error.code : 'failed')
    }
  }

  async function stop(): Promise<void> {
    const current = recorder.current
    if (current === null) return
    recorder.current = null
    setPhase('measuring')
    try {
      const take = await current.stop()
      onVideo(await measureVideo(take.bytes, take.durationMs, take.poster))
    } catch (error) {
      setFailure(error instanceof VideoError ? error.code : 'failed')
    } finally {
      current.close()
      setPhase('idle')
    }
  }

  if (!supported) return <p className="field-hint">{t('videoUnsupported')}</p>

  const shooting = phase === 'camera' || phase === 'recording' || phase === 'measuring'
  return (
    <div className="video-draft">
      {shooting ? (
        <div className="video-shoot">
          <div className="video-viewfinder">
            <video ref={viewfinder} muted playsInline aria-hidden="true" />
            <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true"><path d="M7 19V7H19M81 7H93V19M93 81V93H81M19 93H7V81" /></svg>
          </div>
          <div className="voice-row">
            {phase === 'recording' ? (
              <button type="button" className="primary voice-record is-recording" onClick={() => { void stop() }}>
                <span className="voice-dot" aria-hidden="true" />{t('videoStop')}
              </button>
            ) : (
              <button type="button" className="primary voice-record" disabled={phase !== 'camera'} onClick={record}>
                <span className="voice-dot" aria-hidden="true" />{t('videoStart')}
              </button>
            )}
            <span className="voice-time" role="timer">{voiceLength(Math.max(0, elapsed))} / {voiceLength(VIDEO_MAX_MS)}</span>
            {phase !== 'measuring' ? <button type="button" className="quiet" onClick={close}>{t('videoCancel')}</button> : null}
          </div>
        </div>
      ) : video !== null ? (
        <div className="voice-row">
          <div className="video-viewfinder is-small">
            {url !== null ? <video src={url} muted loop autoPlay playsInline aria-hidden="true" /> : null}
            <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true"><path d="M7 19V7H19M81 7H93V19M93 81V93H81M19 93H7V81" /></svg>
          </div>
          <span className="voice-time">{voiceLength(video.durationMs)}</span>
          <button type="button" disabled={props.disabled} onClick={open}>{t('videoRerecord')}</button>
          <button type="button" className="quiet" disabled={props.disabled} onClick={() => onVideo(null)}>{t('videoDelete')}</button>
        </div>
      ) : (
        <div className="voice-row">
          <button type="button" disabled={props.disabled || props.photosFull} onClick={open}>
            <span className="voice-dot" aria-hidden="true" />{t('videoRecord')}
          </button>
        </div>
      )}
      {failure !== null
        ? <p role="alert">{t(FAILURE[failure])}</p>
        : <p className="field-hint">{t(props.photosFull && video === null ? 'videoPhotosFull' : 'videoHint')}</p>}
    </div>
  )
}
