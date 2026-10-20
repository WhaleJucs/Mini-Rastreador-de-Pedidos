import { resolveEnv } from './env-validation.js';

export const env = resolveEnv(import.meta.env);
