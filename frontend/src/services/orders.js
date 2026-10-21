const ORDER_STATUSES = new Set([
  'RECEBIDO', 'EM_PREPARO', 'SAIU_PARA_ENTREGA', 'ENTREGUE', 'CANCELADO',
]);

const UTC_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;

export class OrdersResponseError extends Error {
  constructor() {
    super('O serviço retornou uma resposta de pedidos inválida. Tente atualizar novamente.');
    this.name = 'OrdersResponseError';
  }
}

function isPositiveInteger(value) {
  return Number.isSafeInteger(value) && value > 0;
}

function isText(value, maxLength) {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= maxLength;
}

function orderFrom(data) {
  const address = data?.deliveryAddress;
  if (
    !isPositiveInteger(data?.id)
    || !isText(data?.customerName, 100)
    || !ORDER_STATUSES.has(data?.status)
    || !Number.isSafeInteger(data?.version)
    || data.version < 0
    || typeof data?.createdAt !== 'string'
    || !UTC_INSTANT.test(data.createdAt)
    || !Number.isFinite(Date.parse(data.createdAt))
    || !isText(address?.street, 150)
    || !isText(address?.number, 30)
    || !isText(address?.city, 150)
    || typeof address?.state !== 'string'
    || !/^[A-Z]{2}$/.test(address.state)
    || !(address.zipCode === null || (typeof address.zipCode === 'string' && /^\d{8}$/.test(address.zipCode)))
    || !Array.isArray(data?.items)
    || data.items.length === 0
  ) {
    throw new OrdersResponseError();
  }

  const itemIds = new Set();
  const items = data.items.map(item => {
    if (
      !isPositiveInteger(item?.id)
      || itemIds.has(item.id)
      || !isText(item?.productName, 100)
      || !isPositiveInteger(item?.quantity)
      || item.quantity > 2_147_483_647
    ) {
      throw new OrdersResponseError();
    }
    itemIds.add(item.id);
    return { id: item.id, productName: item.productName, quantity: item.quantity };
  });

  return {
    id: data.id,
    customerName: data.customerName,
    deliveryAddress: {
      street: address.street,
      number: address.number,
      city: address.city,
      state: address.state,
      zipCode: address.zipCode,
    },
    items,
    status: data.status,
    createdAt: data.createdAt,
    version: data.version,
  };
}

export function ordersResultFrom(response) {
  if (response?.status !== 200 || !Array.isArray(response?.data)) {
    throw new OrdersResponseError();
  }
  const orderIds = new Set();
  return response.data.map(data => {
    const order = orderFrom(data);
    if (orderIds.has(order.id)) throw new OrdersResponseError();
    orderIds.add(order.id);
    return order;
  });
}

export function createdOrderResultFrom(response) {
  if (response?.status !== 201) throw new OrdersResponseError();
  const order = orderFrom(response.data);
  if (order.status !== 'RECEBIDO' || order.version !== 0) throw new OrdersResponseError();
  return order;
}

export function ordersErrorMessage(error) {
  if (error instanceof OrdersResponseError) return error.message;
  if (error?.response?.status === 401) return 'Sua sessão expirou. Entre novamente.';
  if (error?.response?.status === 503) {
    return 'O serviço está temporariamente ocupado. Tente atualizar em instantes.';
  }
  if (!error?.response) {
    return 'Não foi possível carregar os pedidos. Verifique sua conexão e tente novamente.';
  }
  return 'Não foi possível carregar os pedidos. Tente atualizar novamente.';
}
