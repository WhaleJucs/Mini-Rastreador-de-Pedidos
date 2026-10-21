import { allowedOrderStatuses } from '../../services/orderStatusUpdate.js';
import { orderStatusLabel } from '../../utils/orderStatusLabel.js';

export default function OrderStatusControl({ order, state = {}, onSubmit, onReload }) {
  const destinations = allowedOrderStatuses(order.status);
  const disabled = state.isSubmitting || Boolean(state.error);

  function confirmChange(status) {
    if (disabled || !destinations.includes(status)) return;
    if (!window.confirm(`Alterar o pedido #${order.id} de ${orderStatusLabel(order.status)} para ${orderStatusLabel(status)}?`)) return;
    onSubmit(order, status);
  }

  return (
    <div className="order-status-control" aria-busy={Boolean(state.isSubmitting || state.isReloading)}>
      {destinations.length > 0 && (
        <div className="order-status-actions" role="group" aria-label={`Alterar status do pedido #${order.id}`}>
          {destinations.map(status => (
            <button key={status} className={status === 'CANCELADO' ? 'secondary-button' : 'primary-button'}
              type="button" aria-busy={Boolean(state.isSubmitting)} disabled={disabled}
              onClick={() => confirmChange(status)}>
              {status === 'CANCELADO' ? 'Cancelar pedido' : `${orderStatusLabel(status)}`}
            </button>
          ))}
        </div>
      )}
      {state.isSubmitting && <p className="order-status-progress" role="status">Alterando status…</p>}
      {state.error && (
        <div className="form-message form-message-error" role="alert">
          <p>{state.error}</p>
          <button className="secondary-button" type="button" onClick={() => onReload(order.id)} disabled={state.isReloading}>
            {state.isReloading ? 'Recarregando…' : 'Recarregar dados do pedido'}
          </button>
        </div>
      )}
      {state.success && <p className="form-message form-message-success" role="status">{state.success}</p>}
    </div>
  );
}
