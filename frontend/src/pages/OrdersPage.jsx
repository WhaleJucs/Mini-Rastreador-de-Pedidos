import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import OrderCard from '../components/orders/OrderCard.jsx';
import OrderStatusControl from '../components/orders/OrderStatusControl.jsx';
import { useAuth } from '../hooks/useAuth.js';
import { useOrders } from '../hooks/useOrders.js';
import { useUpdateOrderStatus } from '../hooks/useUpdateOrderStatus.js';

export default function OrdersPage() {
  const { logout, user } = useAuth();
  const { orders, hasLoaded, isLoading, error, refresh, invalidate, applyConfirmed } = useOrders();
  const statusUpdate = useUpdateOrderStatus({ invalidate, applyConfirmed, refresh });
  const navigate = useNavigate();
  const location = useLocation();
  const [search, setSearch] = useState('');
  const searchTerm = search.trim().toLocaleLowerCase('pt-BR');
  const filteredOrders = searchTerm
    ? orders.filter(order => String(order.id).includes(searchTerm)
      || order.customerName.toLocaleLowerCase('pt-BR').includes(searchTerm))
    : orders;
  const [createdOrderId] = useState(() => {
    const id = location.state?.createdOrderId;
    return Number.isSafeInteger(id) && id > 0 ? id : null;
  });

  useEffect(() => {
    if (location.state?.createdOrderId !== undefined) {
      navigate(location.pathname, { replace: true, state: null });
    }
  }, [location.pathname, location.state, navigate]);

  function handleLogout() {
    logout();
    navigate('/login', { replace: true });
  }

  return (
    <main className="protected-page">
      <header className="app-header">
        <Link className="wordmark wordmark-static" to="/orders">
          <span aria-hidden="true">F</span>
          Foody Delivery
        </Link>
        <div className="app-session">
          <span>{user.name}</span>
          <button className="secondary-button" type="button" onClick={handleLogout}>Sair</button>
        </div>
      </header>

      <section className="orders-content" aria-labelledby="orders-title">
        <header className="orders-heading">
          <div>
            <p className="eyebrow">Acompanhe as entregas</p>
            <h1 id="orders-title">Pedidos</h1>
            <p>Seus pedidos, dos mais recentes aos mais antigos.</p>
          </div>
          <div className="orders-actions">
            <button className="secondary-button" type="button" onClick={refresh} disabled={isLoading}>
              {isLoading && hasLoaded ? 'Atualizando…' : 'Atualizar pedidos'}
            </button>
            <Link className="primary-link" to="/orders/new">Novo pedido</Link>
          </div>
        </header>

        <div className="form-field orders-search" role="search" aria-label="Pesquisar pedidos">
          <input id="orders-search" type="search" value={search}
            placeholder="Pesquisar por ID ou nome do cliente"
            onChange={event => setSearch(event.target.value)} />
        </div>

        {createdOrderId && (
          <div className="form-message form-message-success" role="status">
            Pedido #{createdOrderId} criado com sucesso.
          </div>
        )}

        <p className="orders-loading" role="status" aria-live="polite">
          {isLoading ? (hasLoaded ? 'Atualizando pedidos…' : 'Carregando pedidos…') : ''}
        </p>

        {error && (
          <div className="form-message form-message-error orders-error" role="alert">
            <p>{error}</p>
            {hasLoaded && <p>Os dados exibidos são da última atualização concluída.</p>}
            <button className="secondary-button" type="button" onClick={refresh} disabled={isLoading}>
              Tentar novamente
            </button>
          </div>
        )}

        <div aria-busy={isLoading}>
          {hasLoaded && orders.length === 0 && !error && !isLoading && (
            <div className="orders-empty" role="status">
              <h2>Nenhum pedido cadastrado</h2>
              <p>Crie o primeiro pedido para acompanhar as entregas por aqui.</p>
              <Link className="text-link" to="/orders/new">Criar pedido</Link>
            </div>
          )}
          {hasLoaded && orders.length > 0 && filteredOrders.length === 0 && !error && !isLoading && (
            <div className="orders-empty" role="status">
              <h2>Nenhum pedido encontrado</h2>
              <p>Tente outro ID ou nome do cliente.</p>
            </div>
          )}
          {filteredOrders.length > 0 && (
            <ul className="orders-list" aria-label="Pedidos cadastrados">
              {filteredOrders.map(order => (
                <li key={order.id}>
                  <OrderCard order={order}>
                    <OrderStatusControl order={order} state={statusUpdate.states[order.id]}
                      onSubmit={statusUpdate.submit} onReload={statusUpdate.reload} />
                  </OrderCard>
                </li>
              ))}
            </ul>
          )}
        </div>
      </section>
    </main>
  );
}
