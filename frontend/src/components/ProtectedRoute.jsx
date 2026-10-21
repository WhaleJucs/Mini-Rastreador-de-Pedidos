import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuth } from '../hooks/useAuth.js';

export function ProtectedRoute() {
  const { isAuthenticated, sessionEndReason } = useAuth();
  const location = useLocation();

  if (isAuthenticated) return <Outlet />;

  const authReason = ['expired', 'unauthorized'].includes(sessionEndReason)
    ? sessionEndReason
    : 'required';
  return (
    <Navigate
      replace
      to="/login"
      state={{ authReason, from: location.pathname }}
    />
  );
}

export function PublicOnlyRoute() {
  const { isAuthenticated } = useAuth();
  return isAuthenticated ? <Navigate replace to="/orders" /> : <Outlet />;
}
