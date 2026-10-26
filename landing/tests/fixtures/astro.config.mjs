import { defineConfig } from 'astro/config';
export default defineConfig({
    output: 'static',
    publicDir: '../../public',
    devToolbar: { enabled: false },
});
