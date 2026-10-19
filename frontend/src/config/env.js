export const env = Object.freeze({
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL?.trim().replace(/\/+$/, '') || '/api',
});
