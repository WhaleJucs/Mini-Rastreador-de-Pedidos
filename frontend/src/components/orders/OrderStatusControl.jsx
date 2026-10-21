import { useState } from 'react';
import { allowedOrderStatuses } from '../../services/orderStatusUpdate.js';
import OrderStatusSelect from './OrderStatusSelect.jsx';
import { orderStatusLabel } from '../../utils/orderStatusLabel.js';

export default function OrderStatusControl({ order, state = {}, onSubmit, onReload }) {
  const [choice, setChoice] = useState(null);
  const destinations = allowedOrderStatuses(order.status);
  const selected = choice?.version === order.version ? choice.status : '';
  const disabled = state.isSubmitting || Boolean(state.error);

  function confirmChange(event) {
    event.preventDefault();
    if (disabled || !destinations.includes(selected)) return;
    if (!window.confirm(`Alterar o pedido #${order.id} de ${orderStatusLabel(order.status)} para ${orderStatusLabel(selected)}?`)) return;
    onSubmit(order, selected);
    setChoice(null);
  }

  async function reloadOrder() {
    if (await onReload(order.id)) setChoice(null);
  }

  return (
    <div className="order-status-control" aria-busy={Boolean(state.isSubmitting || state.isReloading)}>
      {destinations.length > 0 && (
        <form className="order-status-form" onSubmit={confirmChange}>
          <div className="form-field">
            <label id={`order-next-status-${order.id}-label`} htmlFor={`order-next-status-${order.id}`}>Novo status</label>
            <OrderStatusSelect key={order.version} id={`order-next-status-${order.id}`} value={selected}
              options={destinations} disabled={disabled}
              onChange={status => setChoice({ version: order.version, status })} />
          </div>
          <button className="secondary-button" type="submit" aria-busy={Boolean(state.isSubmitting)}
            disabled={disabled || !destinations.includes(selected)}>
            {state.isSubmitting ? 'Alterando status…' : 'Alterar status'}
          </button>
        </form>
      )}
      {state.error && (
        <div className="form-message form-message-error" role="alert">
          <p>{state.error}</p>
          <button className="secondary-button" type="button" onClick={reloadOrder} disabled={state.isReloading}>
            {state.isReloading ? 'Recarregando…' : 'Recarregar dados do pedido'}
          </button>
        </div>
      )}
      {state.success && <p className="form-message form-message-success" role="status">{state.success}</p>}
    </div>
  );
}
