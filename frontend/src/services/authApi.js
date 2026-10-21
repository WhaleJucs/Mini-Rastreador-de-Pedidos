import api from './api.js';
import { registrationErrorFrom, registrationResultFrom } from './registration.js';

export async function login(credentials, { signal } = {}) {
  const response = await api.post('/auth/login', credentials, { signal, skipAuth: true });
  return response.data;
}

export async function register(user, { signal } = {}) {
  let response;
  try {
    response = await api.post('/auth/register', user, { signal, skipAuth: true });
  } catch (error) {
    if (error?.code === 'ERR_CANCELED') throw error;
    throw registrationErrorFrom(error);
  }
  return registrationResultFrom(response);
}
