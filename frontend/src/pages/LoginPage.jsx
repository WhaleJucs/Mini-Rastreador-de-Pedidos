import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { useAuth } from '../hooks/useAuth.js';
import { validateLogin } from '../services/login.js';

const EMPTY_FORM = { email: '', password: '' };
const PROTECTED_DESTINATIONS = new Set(['/orders', '/orders/new']);

function accessMessage(state) {
  if (state?.automaticLoginFailed) return 'Sua conta foi criada, mas não foi possível entrar automaticamente. Entre com sua conta.';
  if (state?.registrationCompleted) return 'Cadastro concluído. Entre com sua conta.';
  if (state?.authReason === 'expired') return 'Sua sessão expirou. Entre novamente para continuar.';
  if (state?.authReason === 'unauthorized') return 'Sua sessão não é mais válida. Entre novamente.';
  if (state?.authReason === 'required') return 'Entre para acessar seus pedidos.';
  return '';
}

export default function LoginPage() {
  const { login } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [form, setForm] = useState(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState({});
  const [generalError, setGeneralError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const submittingRef = useRef(false);
  const controllerRef = useRef(null);
  const emailRef = useRef(null);
  const passwordRef = useRef(null);

  useEffect(() => () => controllerRef.current?.abort(), []);

  function updateField(event) {
    const { name, value } = event.target;
    setForm(current => ({ ...current, [name]: value }));
    setFieldErrors(current => {
      if (current[name] === undefined) return current;
      const next = { ...current };
      delete next[name];
      return next;
    });
    setGeneralError('');
  }

  function focusFirstError(errors) {
    const refs = { email: emailRef, password: passwordRef };
    const firstField = ['email', 'password'].find(field => errors[field]);
    refs[firstField]?.current?.focus();
  }

  async function handleSubmit(event) {
    event.preventDefault();
    if (submittingRef.current) return;

    const clientErrors = validateLogin(form);
    if (Object.keys(clientErrors).length > 0) {
      setFieldErrors(clientErrors);
      setGeneralError('Revise os campos destacados.');
      focusFirstError(clientErrors);
      return;
    }

    submittingRef.current = true;
    setIsSubmitting(true);
    setFieldErrors({});
    setGeneralError('');
    const controller = new AbortController();
    controllerRef.current = controller;

    try {
      await login(form, { signal: controller.signal });
      const destination = PROTECTED_DESTINATIONS.has(location.state?.from)
        ? location.state.from
        : '/orders';
      navigate(destination, { replace: true });
    } catch (error) {
      if (error?.code === 'ERR_CANCELED') return;
      const nextFieldErrors = error?.fieldErrors ?? {};
      setFieldErrors(nextFieldErrors);
      setGeneralError(error?.message ?? 'Não foi possível entrar. Tente novamente.');
      focusFirstError(nextFieldErrors);
    } finally {
      if (controllerRef.current === controller) controllerRef.current = null;
      submittingRef.current = false;
      setIsSubmitting(false);
    }
  }

  const statusMessage = accessMessage(location.state);

  return (
    <main className="auth-page">
      <section className="auth-intro" aria-labelledby="login-heading">
        <Link className="wordmark" to="/" aria-label="Foody Delivery — início">
          <span aria-hidden="true">F</span>
          Foody Delivery
        </Link>
        <div className="auth-intro-copy">
          <p className="eyebrow">Seus pedidos, em um só lugar</p>
          <h1 id="login-heading">Acompanhe cada etapa da entrega.</h1>
          <p>Entre com sua conta para consultar e criar pedidos com segurança.</p>
        </div>
        <p className="auth-footnote">Simples para pedir. Claro para acompanhar.</p>
      </section>

      <section className="auth-panel" aria-label="Entrar">
        <div className="auth-card">
          <div className="auth-card-heading">
            <p className="eyebrow">Acesso</p>
            <h2>Entre na sua conta</h2>
            <p>Informe seu e-mail e sua senha.</p>
          </div>

          {statusMessage && !generalError && (
            <div
              className={`form-message ${location.state?.registrationCompleted && !location.state?.automaticLoginFailed ? 'form-message-success' : 'form-message-info'}`}
              role="status"
            >
              {statusMessage}
            </div>
          )}

          {generalError && (
            <div className="form-message form-message-error" role="alert">
              {generalError}
            </div>
          )}

          <form className="auth-form" onSubmit={handleSubmit} noValidate aria-busy={isSubmitting}>
            <div className="form-field">
              <label htmlFor="login-email">E-mail</label>
              <input
                ref={emailRef}
                id="login-email"
                name="email"
                type="email"
                inputMode="email"
                autoComplete="email"
                value={form.email}
                onChange={updateField}
                aria-invalid={Boolean(fieldErrors.email)}
                aria-describedby={fieldErrors.email ? 'login-email-error' : undefined}
                disabled={isSubmitting}
              />
              {fieldErrors.email && <p id="login-email-error" className="field-error">{fieldErrors.email}</p>}
            </div>

            <div className="form-field">
              <label htmlFor="login-password">Senha</label>
              <input
                ref={passwordRef}
                id="login-password"
                name="password"
                type="password"
                autoComplete="current-password"
                value={form.password}
                onChange={updateField}
                aria-invalid={Boolean(fieldErrors.password)}
                aria-describedby={fieldErrors.password ? 'login-password-error' : undefined}
                disabled={isSubmitting}
              />
              {fieldErrors.password && <p id="login-password-error" className="field-error">{fieldErrors.password}</p>}
            </div>

            <button className="primary-button" type="submit" disabled={isSubmitting}>
              {isSubmitting ? 'Entrando…' : 'Entrar'}
            </button>
          </form>

          <p className="auth-switch">
            Ainda não tem conta? <Link to="/register">Criar conta</Link>
          </p>
        </div>
      </section>
    </main>
  );
}
