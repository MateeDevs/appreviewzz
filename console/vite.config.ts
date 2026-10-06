import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// Console se buildí do statických souborů, které servíruje Ktor ze stejného image
// (ADR 0008 — odpadá CloudFront i S3). Při vývoji běží Vite zvlášť a API si proxuje,
// aby cookie se session platila na stejném originu jako v produkci.
export default defineConfig(({ mode }) => {
  // API běžně na 8080; kdo má vedle sebe dvě instance (třeba dump stagingu), přepne
  // `VITE_API_URL=http://localhost:8090 npm run dev`.
  const api = loadEnv(mode, '.', 'VITE_').VITE_API_URL || 'http://localhost:8080'

  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/api': { target: api, changeOrigin: false },
        '/slack': { target: api, changeOrigin: false },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: true,
    },
  }
})
