import { useEffect, useRef } from 'react'
import type { MessageKey } from '../i18n/catalog'

export type CameraFailure = { notice: 'cameraDenied' | 'cameraUnavailable' }

/** A failed user-requested camera action must be visible at its own controls. */
export function CameraFeedback({ failure, t }: { failure: CameraFailure; t: (key: MessageKey) => string }) {
  const feedback = useRef<HTMLDivElement>(null)
  useEffect(() => {
    feedback.current?.focus({ preventScroll: true })
    feedback.current?.scrollIntoView({ block: 'start', behavior: 'instant' })
  }, [failure])
  return <div ref={feedback} className="notice camera-feedback" role="alert" tabIndex={-1}>
    <p className="camera-feedback-title">{t(failure.notice)}</p>
    <p>{t(failure.notice === 'cameraDenied' ? 'cameraDeniedHelp' : 'cameraUnavailableHelp')}</p>
  </div>
}
