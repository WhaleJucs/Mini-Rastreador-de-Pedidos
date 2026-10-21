import { Link, useNavigate } from 'react-router';
import { useAuth } from '../hooks/useAuth.js';

export default function ProtectedAreaPage({ newOrder = false }) {
  const { logout, user } = useAuth();
  const navigate = useNavigate();

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

      <section className="protected-card">
        <p className="eyebrow">Área autenticada</p>
        <h1>{newOrder ? 'Novo pedido' : 'Seus pedidos'}</h1>
        <p>
          {newOrder
            ? 'O formulário de criação será conectado na próxima etapa.'
            : 'A listagem de pedidos será conectada na próxima etapa.'}
        </p>
        <div className="protected-actions">
          {newOrder
            ? <Link className="text-link" to="/orders">Voltar aos pedidos</Link>
            : <Link className="primary-link" to="/orders/new">Novo pedido</Link>}
        </div>
      </section>
    </main>
  );
}
