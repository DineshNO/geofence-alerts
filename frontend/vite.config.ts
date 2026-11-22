/// <reference types="vitest" />
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// In development the console talks to a backend on port 8080. In the delivered system the load
// balancer serves the console and forwards /api, /health and /metrics to the backends, so the
// console always uses relative addresses and never calls another host.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/health': 'http://localhost:8080',
      '/metrics': 'http://localhost:8080',
    },
  },
  build: { sourcemap: true },
  test: { environment: 'jsdom', include: ['src/**/*.test.{ts,tsx}'] },
});
