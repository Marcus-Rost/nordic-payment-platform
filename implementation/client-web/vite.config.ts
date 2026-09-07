import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// JRPay Client Web — Vite config.
//
// Dev server runs on http://localhost:5173 (matches identity OAuth callback).
// Backend services proxy through to canonical compose ports so the SPA stays
// same-origin in dev. Production goes through nginx (docker/client-web/nginx.conf).

export default defineConfig({
    plugins: [react()],
    server: {
        port: 5173,
        host: true,
        proxy: {
            '/oauth2': { target: 'http://localhost:8097', changeOrigin: true },
            '/sca': { target: 'http://localhost:8097', changeOrigin: true },
            '/.well-known': { target: 'http://localhost:8097', changeOrigin: true },
            '/api/v1/customer/accounts': {
                target: 'http://localhost:8091',
                changeOrigin: true,
            },
            '/api/v1/customer/payments': {
                target: 'http://localhost:8091',
                changeOrigin: true,
            },
            '/api/v1/customer/mandates': {
                target: 'http://localhost:8093',
                changeOrigin: true,
            },
            '/api/v1/customer/r2p': {
                target: 'http://localhost:8093',
                changeOrigin: true,
            },
            '/api/v1/customer/beneficiaries': {
                target: 'http://localhost:8091',
                changeOrigin: true,
            },
            '/api/v1/customer/consents': {
                target: 'http://localhost:8094',
                changeOrigin: true,
            },
            '/api/v1/customer/notifications': {
                target: 'http://localhost:8098',
                changeOrigin: true,
            },
            '/api/v1/customer/profile': {
                target: 'http://localhost:8097',
                changeOrigin: true,
            },
            '/api/v1/customer/gdpr': {
                target: 'http://localhost:8091',
                changeOrigin: true,
            },
            '/api/v1/customer/privacy': {
                target: 'http://localhost:8097',
                changeOrigin: true,
            },
            '/api/v1/public/status': {
                target: 'http://localhost:8090',
                changeOrigin: true,
            },
        },
    },
    build: {
        target: 'es2022',
        sourcemap: true,
        rollupOptions: {
            output: {
                manualChunks: {
                    react: ['react', 'react-dom', 'react-router-dom'],
                    i18n: ['i18next', 'react-i18next'],
                },
            },
        },
    },
});
