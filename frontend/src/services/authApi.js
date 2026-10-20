import api from './api.js';

export async function login(credentials, { signal } = {}) {
  const response = await api.post('/auth/login', credentials, { signal, skipAuth: true });
  return response.data;
}

export async function register(user, { signal } = {}) {
  const response = await api.post('/auth/register', user, { signal, skipAuth: true });
  return response.data;
}
