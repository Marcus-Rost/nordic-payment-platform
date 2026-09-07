import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Vite 6 plugin types are incompatible with vitest 2.x's pinned vite 5; cast
// through `never` to bridge the duplicate type-graphs.
export default defineConfig({
    plugins: [react() as unknown as never],
    test: {
        environment: 'jsdom',
        globals: true,
        setupFiles: ['./src/test/setup.ts'],
        include: ['src/**/*.test.{ts,tsx}'],
    },
});
