import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// JRPay Admin Web — Vite config.
//
// In dev, the SPA runs at http://localhost:5174 (matching the OAuth2 redirect
// URI seeded in identity V002). All API calls go directly to the backend
// services on their docker-compose host ports; CORS is handled by Spring's
// resource-server config (or the browser's same-origin via the proxy below
// for local-only convenience).

export default defineConfig({
    plugins: [react()],
    server: {
        port: 5174,
        host: true,
        // Optional dev proxy — keeps API calls same-origin in dev so cookies
        // and CORS preflights stay simple. Production goes through nginx.
        proxy: {
            '/oauth2': {
                target: 'http://localhost:8097',
                changeOrigin: true,
            },
            '/sca': {
                target: 'http://localhost:8097',
                changeOrigin: true,
            },
            '/.well-known': {
                target: 'http://localhost:8097',
                changeOrigin: true,
            },
            '/api/v1/admin/accounts': {
                target: 'http://localhost:8091',
                changeOrigin: true,
            },
            '/api/v1/admin/audit': {
                target: 'http://localhost:8099',
                changeOrigin: true,
            },
            '/api/v1/admin/fraud': {
                target: 'http://localhost:8095',
                changeOrigin: true,
            },
            '/api/v1/admin/sanctions': {
                target: 'http://localhost:8095',
                changeOrigin: true,
            },
            '/api/v1/admin/system': {
                target: 'http://localhost:8090',
                changeOrigin: true,
            },
            '/api/v1/admin/status': {
                target: 'http://localhost:8090',
                changeOrigin: true,
            },
            '/api/v1/admin/users': {
                target: 'http://localhost:8097',
                changeOrigin: true,
            },
            '/api/v1/admin/payments': {
                target: 'http://localhost:8091',
                changeOrigin: true,
            },
            '/api/v1/admin/gdpr': {
                target: 'http://localhost:8091',
                changeOrigin: true,
            },
            '/api/v1/admin/reconciliation': {
                target: 'http://localhost:8091',
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
