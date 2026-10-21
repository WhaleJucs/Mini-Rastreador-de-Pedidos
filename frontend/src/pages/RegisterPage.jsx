import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useAuth } from '../hooks/useAuth.js';
import { validateRegistration } from '../services/registration.js';

const EMPTY_FORM = { name: '', email: '', password: '' };

export default function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState({});
  const [generalError, setGeneralError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const submittingRef = useRef(false);
  const controllerRef = useRef(null);
  const nameRef = useRef(null);
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
    const refs = { name: nameRef, email: emailRef, password: passwordRef };
    const firstField = ['name', 'email', 'password'].find(field => errors[field]);
    refs[firstField]?.current?.focus();
  }

  async function handleSubmit(event) {
    event.preventDefault();
    if (submittingRef.current) return;

    const clientErrors = validateRegistration(form);
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
      await register(form, { signal: controller.signal });
      navigate('/login', { replace: true, state: { registrationCompleted: true } });
    } catch (error) {
      if (error?.code === 'ERR_CANCELED') return;
      const nextFieldErrors = error?.fieldErrors ?? {};
      setFieldErrors(nextFieldErrors);
      setGeneralError(error?.message ?? 'Não foi possível concluir o cadastro. Tente novamente.');
      focusFirstError(nextFieldErrors);
    } finally {
      if (controllerRef.current === controller) controllerRef.current = null;
      submittingRef.current = false;
      setIsSubmitting(false);
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-intro" aria-labelledby="register-heading">
        <Link className="wordmark" to="/" aria-label="Foody Delivery — início">
          <span aria-hidden="true">F</span>
          Foody Delivery
        </Link>
        <div className="auth-intro-copy">
          <p className="eyebrow">Seus pedidos, em um só lugar</p>
          <h1 id="register-heading">Comece a acompanhar cada entrega.</h1>
          <p>Crie sua conta para acessar o rastreador de pedidos com segurança.</p>
        </div>
        <p className="auth-footnote">Simples para pedir. Claro para acompanhar.</p>
      </section>

      <section className="auth-panel" aria-label="Criar conta">
        <div className="auth-card">
          <div className="auth-card-heading">
            <p className="eyebrow">Cadastro</p>
            <h2>Crie sua conta</h2>
            <p>Preencha seus dados para começar.</p>
          </div>

          {generalError && (
            <div className="form-message form-message-error" role="alert">
              {generalError}
            </div>
          )}

          <form className="auth-form" onSubmit={handleSubmit} noValidate>
            <div className="form-field">
              <label htmlFor="register-name">Nome</label>
              <input
                ref={nameRef}
                id="register-name"
                name="name"
                type="text"
                autoComplete="name"
                value={form.name}
                onChange={updateField}
                aria-invalid={Boolean(fieldErrors.name)}
                aria-describedby={fieldErrors.name ? 'register-name-error' : undefined}
                disabled={isSubmitting}
              />
              {fieldErrors.name && <p id="register-name-error" className="field-error">{fieldErrors.name}</p>}
            </div>

            <div className="form-field">
              <label htmlFor="register-email">E-mail</label>
              <input
                ref={emailRef}
                id="register-email"
                name="email"
                type="email"
                inputMode="email"
                autoComplete="email"
                value={form.email}
                onChange={updateField}
                aria-invalid={Boolean(fieldErrors.email)}
                aria-describedby={fieldErrors.email ? 'register-email-error' : undefined}
                disabled={isSubmitting}
              />
              {fieldErrors.email && <p id="register-email-error" className="field-error">{fieldErrors.email}</p>}
            </div>

            <div className="form-field">
              <label htmlFor="register-password">Senha</label>
              <input
                ref={passwordRef}
                id="register-password"
                name="password"
                type="password"
                autoComplete="new-password"
                value={form.password}
                onChange={updateField}
                aria-invalid={Boolean(fieldErrors.password)}
                aria-describedby={fieldErrors.password ? 'register-password-error' : 'register-password-hint'}
                disabled={isSubmitting}
              />
              {fieldErrors.password
                ? <p id="register-password-error" className="field-error">{fieldErrors.password}</p>
                : <p id="register-password-hint" className="field-hint">Até 72 bytes; espaços digitados fazem parte da senha.</p>}
            </div>

            <button className="primary-button" type="submit" disabled={isSubmitting}>
              {isSubmitting ? 'Criando conta…' : 'Criar conta'}
            </button>
          </form>

          <p className="auth-switch">
            Já tem uma conta? <Link to="/login">Entrar</Link>
          </p>
        </div>
      </section>
    </main>
  );
}
