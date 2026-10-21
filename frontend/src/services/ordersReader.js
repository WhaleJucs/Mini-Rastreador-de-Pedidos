import { ordersErrorMessage } from './orders.js';

export function createOrdersReader({ load, onChange }) {
  let generation = 0;
  let controller = null;
  let disposed = false;
  let state = { orders: [], hasLoaded: false, isLoading: false, error: null };

  function publish(change) {
    state = { ...state, ...change };
    onChange(state);
  }

  function invalidate() {
    if (disposed) return;
    generation += 1;
    controller?.abort();
    controller = null;
    publish({ isLoading: false, error: null });
  }

  async function refresh() {
    if (disposed) return;
    const currentGeneration = ++generation;
    controller?.abort();
    const currentController = new AbortController();
    controller = currentController;
    const isCurrent = () => !disposed
      && generation === currentGeneration
      && !currentController.signal.aborted;
    publish({ isLoading: true, error: null });

    try {
      const orders = await load({ signal: currentController.signal });
      if (isCurrent()) {
        publish({ orders, hasLoaded: true, isLoading: false });
        return orders;
      }
    } catch (error) {
      if (!isCurrent()) return;
      publish({
        isLoading: false,
        error: error?.code === 'ERR_CANCELED' ? null : ordersErrorMessage(error),
      });
    } finally {
      if (isCurrent()) controller = null;
    }
    return null;
  }

  function applyConfirmed(order) {
    if (disposed) return;
    invalidate();
    publish({
      orders: state.orders.map(current => current.id === order.id && current.version <= order.version ? order : current),
    });
  }

  function dispose() {
    disposed = true;
    generation += 1;
    controller?.abort();
    controller = null;
  }

  return Object.freeze({ refresh, invalidate, applyConfirmed, dispose });
}
