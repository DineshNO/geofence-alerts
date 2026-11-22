/// <reference types="vitest" />
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

// In development the console talks to a backend on port 8080. In the delivered system the load
// balancer serves the console and forwards /api, /health and /metrics to the backends, so the
// console always uses relative addresses and never calls another host.
export default defineConfig(({ mode }) => {
  // BACKEND_URL points the development proxy at a backend on another port
  const backend = loadEnv(mode, '.', '').BACKEND_URL ?? 'http://localhost:8080';
  return {
    plugins: [react()],
    server: { proxy: { '/api': backend, '/health': backend, '/metrics': backend } },
    build: { sourcemap: true },
    test: { environment: 'jsdom', include: ['src/**/*.test.{ts,tsx}'] },
  };
});
