import axios from 'axios';
import { env } from '../config/env.js';
import { installApiSessionInterceptors } from './apiSession.js';

const api = axios.create({
  baseURL: env.apiBaseUrl,
  timeout: 10000,
});

export function connectApiSession(bridge) {
  return installApiSessionInterceptors(api, bridge);
}

export default api;
