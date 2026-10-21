import { Link, Route, Routes, useLocation } from 'react-router';
import RegisterPage from './pages/RegisterPage.jsx';

function HomePage() {
  return (
    <main className="placeholder-page">
      <p className="wordmark wordmark-static"><span aria-hidden="true">F</span>Foody Delivery</p>
      <h1>Mini Rastreador de Pedidos</h1>
      <p>O acompanhamento dos seus pedidos estará disponível em breve.</p>
      <Link className="text-link" to="/register">Criar conta</Link>
    </main>
  );
}

function LoginHandoff() {
  const location = useLocation();
  return (
    <main className="placeholder-page">
      <p className="wordmark wordmark-static"><span aria-hidden="true">F</span>Foody Delivery</p>
      {location.state?.registrationCompleted && (
        <div className="form-message form-message-success" role="status">
          Cadastro concluído. Entre com sua conta.
        </div>
      )}
      <h1>Entrar</h1>
      <p>A tela de acesso será conectada na próxima unidade da autenticação.</p>
      <Link className="text-link" to="/register">Voltar ao cadastro</Link>
    </main>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/login" element={<LoginHandoff />} />
      <Route path="*" element={<HomePage />} />
    </Routes>
  );
}
