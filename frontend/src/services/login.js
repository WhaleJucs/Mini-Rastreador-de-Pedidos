const LOGIN_FIELDS = new Set(['email', 'password']);

export class LoginError extends Error {
  constructor(message, { fieldErrors = {}, cause } = {}) {
    super(message, { cause });
    this.name = 'LoginError';
    this.fieldErrors = fieldErrors;
  }
}

export function validateLogin({ email = '', password = '' }) {
  const fieldErrors = {};
  const normalizedEmail = email.trim();

  if (!normalizedEmail) fieldErrors.email = 'Informe seu e-mail.';
  else if (normalizedEmail.length > 150) fieldErrors.email = 'Use no máximo 150 caracteres.';
  else if (!/^[^\s@]+@[^\s@]+$/.test(normalizedEmail)) {
    fieldErrors.email = 'Informe um e-mail válido.';
  }

  if (!password.trim()) fieldErrors.password = 'Informe sua senha.';
  else if (new TextEncoder().encode(password).length > 72) {
    fieldErrors.password = 'A senha deve ter no máximo 72 bytes em UTF-8.';
  }

  return fieldErrors;
}

export function loginResultFrom(response) {
  if (response?.status !== 200 || response.data === null || typeof response.data !== 'object') {
    throw new LoginError('O serviço retornou uma resposta inválida. Tente novamente.');
  }
  return response.data;
}

export function loginErrorFrom(error) {
  const problem = error?.response?.data;
  const code = typeof problem?.code === 'string' ? problem.code : null;

  if (code === 'INVALID_CREDENTIALS') {
    return new LoginError('E-mail ou senha inválidos.', { cause: error });
  }

  if (code === 'VALIDATION_ERROR') {
    const fieldErrors = {};
    if (Array.isArray(problem.violations)) {
      for (const violation of problem.violations) {
        if (
          LOGIN_FIELDS.has(violation?.field)
          && typeof violation?.message === 'string'
          && violation.message.trim()
          && fieldErrors[violation.field] === undefined
        ) {
          fieldErrors[violation.field] = violation.message.trim().slice(0, 160);
        }
      }
    }
    return new LoginError('Revise os campos informados.', { fieldErrors, cause: error });
  }

  if (code === 'DATABASE_BUSY') {
    return new LoginError('O acesso está temporariamente indisponível. Tente novamente.', {
      cause: error,
    });
  }

  if (!error?.response) {
    return new LoginError('Não foi possível conectar ao serviço. Verifique sua conexão e tente novamente.', {
      cause: error,
    });
  }

  return new LoginError('Não foi possível entrar. Tente novamente.', { cause: error });
}
