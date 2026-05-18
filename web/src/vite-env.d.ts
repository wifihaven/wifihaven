/// <reference types="vite/client" />

interface ImportMetaEnv {
  // Baked in at Static Site build time — empty string by default so the
  // JVM-bundled SPA continues to use relative API paths (/api/...).
  // Overridden per environment in render.yaml (see #587).
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
