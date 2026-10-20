import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { resolveEnv } from './src/config/env-validation.js';

export default defineConfig(({ mode }) => {
  resolveEnv({ ...loadEnv(mode, process.cwd(), 'VITE_'), MODE: mode });
  return {
    plugins: [react(), {
      name: 'validate-production-environment',
      configResolved(config) {
        // Usa a configuração final, inclusive NODE_ENV recebido por arquivo .env.
        if (['hml', 'production'].includes(config.mode) && !config.isProduction) {
          throw new Error('O build hml/production exige NODE_ENV=production; selecione o ambiente pelo modo Vite.');
        }
      },
    }],
    server: {
      proxy: {
        '/api': 'http://127.0.0.1:8081',
      },
    },
  };
});
