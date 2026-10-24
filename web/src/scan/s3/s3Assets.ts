/**
 * Same-origin frozen-matcher assets (the ONNX card localizer and the visual
 * vocabulary), loaded once per page next to the pinned OpenCV.js runtime.
 */
import { loadOpenCvModule } from '../opencvRuntime'
import { createLocalizer, createVocabulary, type Cv, type Localizer, type Vocabulary } from './frozenMatcher'

const BASE = `${import.meta.env.BASE_URL}frozen-matcher/v1/`

export interface FrozenMatcherAssets {
  readonly cv: Cv
  readonly vocabulary: Vocabulary
  /** Recipient side only; loads the 13 MB model on first use. */
  localizer(): Promise<Localizer>
}

let assets: Promise<FrozenMatcherAssets> | null = null

async function fetchBytes(name: string, signal?: AbortSignal): Promise<Uint8Array> {
  const response = await fetch(`${BASE}${name}`, { credentials: 'omit', redirect: 'error', signal })
  if (response.status !== 200) throw new Error('frozen matcher asset unavailable')
  return new Uint8Array(await response.arrayBuffer())
}

export function loadFrozenMatcher(signal?: AbortSignal): Promise<FrozenMatcherAssets> {
  if (!assets) {
    assets = (async () => {
      const cv = await loadOpenCvModule(signal)
      const vocab = await fetchBytes('s3_vocabulary.bin', signal)
      const vocabulary = createVocabulary(cv, new Float32Array(vocab.buffer, vocab.byteOffset, vocab.byteLength / 4))
      let localizer: Promise<Localizer> | null = null
      return {
        cv,
        vocabulary,
        localizer() {
          if (!localizer) {
            localizer = fetchBytes('model_fold0_seed0.onnx').then((model) => createLocalizer(cv, model))
            localizer.catch(() => { localizer = null })
          }
          return localizer
        },
      }
    })()
    assets.catch(() => { assets = null })
  }
  return assets
}
