/**
 * T312 — narrow typed port over the pinned OpenCV.js runtime used by the SIFT
 * extractor. Only the operations the extractor actually performs are expressed:
 * grayscale Mat construction, SIFT detect/compute, INTER_AREA resize, CV_32F
 * conversion and the 2-D DCT. The real implementation is `opencvRuntime.ts`
 * (loads the vendored same-origin WASM); tests may supply a deterministic
 * test double, but the production path always uses the real compiled runtime.
 *
 * Every returned native object (`CvMat`, `CvSift`) is owned by the caller and
 * must be released with `delete()`.
 */

export interface CvKeyPoint {
  readonly pt: { readonly x: number; readonly y: number }
  readonly size: number
  readonly angle: number
  readonly response: number
  readonly octave: number
}

export interface CvMat {
  readonly rows: number
  readonly cols: number
  /** 8-bit element view (valid for CV_8U Mats). */
  readonly data: Uint8Array
  /** 32-bit float element view (valid for CV_32F Mats). */
  readonly data32F: Float32Array
  type(): number
  delete(): void
}

export interface CvComputeResult {
  /** n x 128 CV_32F descriptors, rows in `points` order. */
  readonly descriptors: CvMat
  /** Key points read back after compute, in `points` order. */
  readonly keypoints: CvKeyPoint[]
}

export interface CvSift {
  detect(gray: CvMat): CvKeyPoint[]
  /** Computes 128-column CV_32F descriptors for `points`, in order. */
  compute(gray: CvMat, points: readonly CvKeyPoint[]): CvComputeResult
  delete(): void
}

export interface SiftParameters {
  readonly nfeatures: number
  readonly octaveLayers: number
  readonly contrastThreshold: number
  readonly edgeThreshold: number
  readonly sigma: number
}

export interface CvRuntime {
  readonly CV_8UC1: number
  readonly CV_32F: number
  readonly INTER_AREA: number
  /** Owns a fresh CV_8UC1 Mat initialized from `gray` (length width*height). */
  matFromGray(gray: Uint8Array, width: number, height: number): CvMat
  createEmptyMat(): CvMat
  createSift(parameters: SiftParameters): CvSift
  /** INTER_AREA resize to a square `size`x`size` CV_8U Mat (owns the result). */
  resizeArea(src: CvMat, size: number): CvMat
  /** CV_32F copy of an 8-bit Mat (owns the result). */
  convertToFloat(src: CvMat): CvMat
  /** Forward 2-D DCT of a CV_32F Mat (owns the result). */
  dct(src: CvMat): CvMat
}
