import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { resolveEnv } from './src/config/env-validation.js';

export default defineConfig(({ mode }) => {
  resolveEnv({ ...loadEnv(mode, process.cwd(), 'VITE_'), MODE: mode });
  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': 'http://127.0.0.1:8081',
      },
    },
  };
});
