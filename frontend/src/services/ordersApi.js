import api from './api.js';
import { createdOrderResultFrom, ordersResultFrom, updatedOrderResultFrom } from './orders.js';

export async function listOrders({ signal } = {}) {
  const response = await api.get('/orders', { signal });
  return ordersResultFrom(response);
}

export async function createOrder(payload, { signal } = {}) {
  const response = await api.post('/orders', payload, { signal });
  return createdOrderResultFrom(response);
}

export async function updateOrderStatus(id, payload, { signal } = {}) {
  const response = await api.patch(`/orders/${id}/status`, payload, { signal });
  return updatedOrderResultFrom(response, id);
}
