import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Console se buildí do statických souborů, které servíruje Ktor ze stejného image
// (ADR 0008 — odpadá CloudFront i S3). Při vývoji běží Vite zvlášť a API si proxuje,
// aby cookie se session platila na stejném originu jako v produkci.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
      '/slack': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
})
