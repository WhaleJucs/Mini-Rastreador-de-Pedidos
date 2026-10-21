import api from './api.js';
import { ordersResultFrom } from './orders.js';

export async function listOrders({ signal } = {}) {
  const response = await api.get('/orders', { signal });
  return ordersResultFrom(response);
}
