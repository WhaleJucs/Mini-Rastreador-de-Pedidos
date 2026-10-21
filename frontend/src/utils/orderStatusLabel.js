const STATUS_LABELS = Object.freeze({
  RECEBIDO: 'Recebido',
  EM_PREPARO: 'Em Preparo',
  SAIU_PARA_ENTREGA: 'Saiu para Entrega',
  ENTREGUE: 'Entregue',
  CANCELADO: 'Cancelado',
});

export function orderStatusLabel(status) {
  return Object.hasOwn(STATUS_LABELS, status) ? STATUS_LABELS[status] : 'Status desconhecido';
}
