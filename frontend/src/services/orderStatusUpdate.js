const DESTINATIONS = Object.freeze({
  RECEBIDO: Object.freeze(['EM_PREPARO', 'CANCELADO']),
  EM_PREPARO: Object.freeze(['SAIU_PARA_ENTREGA', 'CANCELADO']),
  SAIU_PARA_ENTREGA: Object.freeze(['ENTREGUE']),
  ENTREGUE: Object.freeze([]),
  CANCELADO: Object.freeze([]),
});

export function allowedOrderStatuses(status) {
  return Object.hasOwn(DESTINATIONS, status) ? DESTINATIONS[status] : [];
}

export function statusUpdateErrorMessage(error) {
  const status = error?.response?.status;
  if (status === 409) return 'O pedido foi alterado em outra tela. Recarregue os dados e confirme uma nova escolha.';
  if (status === 503) return 'O banco de dados está temporariamente ocupado. Recarregue os dados antes de tentar novamente.';
  if (status === 401) return 'Sua sessão expirou. Entre novamente.';
  if (status === 404) return 'O pedido não foi encontrado. Recarregue a lista de pedidos.';
  if (status === 400) return 'A mudança de status foi recusada. Recarregue os dados e confira as opções disponíveis.';
  if (error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT') {
    return 'O serviço demorou para responder. Recarregue os dados para conferir se o status foi alterado.';
  }
  if (!error?.response && error?.name !== 'OrdersResponseError') {
    return 'Não foi possível confirmar a alteração. Verifique sua conexão e recarregue os dados antes de tentar novamente.';
  }
  return 'Não foi possível confirmar a alteração. Recarregue os dados antes de tentar novamente.';
}

export function createOrderStatusUpdater({ send, reload, onStart, onConfirmed, onChange }) {
  const requests = new Map();
  let disposed = false;

  async function submit(observed, requested) {
    const id = observed.id;
    if (disposed || requests.has(id) || !allowedOrderStatuses(observed.status).includes(requested)) return null;
    const controller = new AbortController();
    const request = { controller };
    requests.set(id, request);
    const isCurrent = () => !disposed && requests.get(id) === request && !controller.signal.aborted;
    onStart();
    onChange(id, { isSubmitting: true, error: null, success: null, isReloading: false });
    let order;
    try {
      order = await send(id, { status: requested, expectedVersion: observed.version }, { signal: controller.signal });
    } catch (error) {
      if (!isCurrent()) return null;
      // Uma intenção antiga fica bloqueada até uma releitura explícita, inclusive após resposta perdida.
      requests.set(id, { blocked: true });
      onChange(id, { isSubmitting: false, error: statusUpdateErrorMessage(error), success: null, isReloading: false });
      return null;
    }
    if (!isCurrent()) return null;
    requests.delete(id);
    onConfirmed(order);
    onChange(id, { isSubmitting: false, error: null, success: `Status confirmado: ${order.status}.`, isReloading: false });
    return order;
  }

  async function refresh(id) {
    const blocked = requests.get(id);
    if (disposed || !blocked?.blocked || blocked.reloading) return false;
    blocked.reloading = true;
    onChange(id, { isSubmitting: false, error: 'Recarregando os dados do pedido…', success: null, isReloading: true });
    const orders = await reload();
    if (disposed || requests.get(id) !== blocked) return false;
    if (!Array.isArray(orders)) {
      blocked.reloading = false;
      onChange(id, { isSubmitting: false, error: 'Não foi possível recarregar. Confira sua conexão e tente recarregar novamente.', success: null, isReloading: false });
      return false;
    }
    requests.delete(id);
    onChange(id, { isSubmitting: false, error: null, success: 'Dados recarregados. Confira o status e faça uma nova escolha.', isReloading: false });
    return true;
  }

  function dispose() {
    disposed = true;
    requests.forEach(request => request.controller?.abort());
    requests.clear();
  }

  return Object.freeze({ submit, refresh, dispose });
}
