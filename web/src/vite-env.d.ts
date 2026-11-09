/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_ORIGIN?: string
  readonly VITE_SENDS_LETTER?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
