import { Navigate, Route, Routes } from 'react-router';
import { ProtectedRoute, PublicOnlyRoute } from './components/ProtectedRoute.jsx';
import { useAuth } from './hooks/useAuth.js';
import LoginPage from './pages/LoginPage.jsx';
import ProtectedAreaPage from './pages/ProtectedAreaPage.jsx';
import RegisterPage from './pages/RegisterPage.jsx';

function HomeRedirect() {
  const { isAuthenticated } = useAuth();
  return <Navigate replace to={isAuthenticated ? '/orders' : '/login'} />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomeRedirect />} />
      <Route element={<PublicOnlyRoute />}>
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/login" element={<LoginPage />} />
      </Route>
      <Route element={<ProtectedRoute />}>
        <Route path="/orders" element={<ProtectedAreaPage />} />
        <Route path="/orders/new" element={<ProtectedAreaPage newOrder />} />
      </Route>
      <Route path="*" element={<HomeRedirect />} />
    </Routes>
  );
}
