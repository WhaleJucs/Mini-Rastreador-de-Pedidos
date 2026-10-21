const REGISTRATION_FIELDS = new Set(['name', 'email', 'password']);

export class RegistrationError extends Error {
  constructor(message, { fieldErrors = {}, cause } = {}) {
    super(message, { cause });
    this.name = 'RegistrationError';
    this.fieldErrors = fieldErrors;
  }
}

export function validateRegistration({ name = '', email = '', password = '' }) {
  const fieldErrors = {};

  const normalizedName = name.trim();
  if (!normalizedName) fieldErrors.name = 'Informe seu nome.';
  else if (normalizedName.length > 100) fieldErrors.name = 'Use no máximo 100 caracteres.';

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

export function registrationResultFrom(response) {
  const user = response?.data;
  if (
    response?.status !== 201
    || !Number.isInteger(user?.id)
    || user.id <= 0
    || typeof user?.name !== 'string'
    || !user.name
    || typeof user?.email !== 'string'
    || !user.email
  ) {
    throw new RegistrationError('O serviço retornou uma resposta inválida. Tente novamente.');
  }
  return { id: user.id, name: user.name, email: user.email };
}

export function registrationErrorFrom(error) {
  const problem = error?.response?.data;
  const code = typeof problem?.code === 'string' ? problem.code : null;

  if (code === 'EMAIL_ALREADY_EXISTS') {
    return new RegistrationError('Revise o e-mail informado.', {
      fieldErrors: { email: 'Este e-mail já está cadastrado.' },
      cause: error,
    });
  }

  if (code === 'VALIDATION_ERROR') {
    const fieldErrors = {};
    if (Array.isArray(problem.violations)) {
      for (const violation of problem.violations) {
        if (
          REGISTRATION_FIELDS.has(violation?.field)
          && typeof violation?.message === 'string'
          && violation.message.trim()
          && fieldErrors[violation.field] === undefined
        ) {
          fieldErrors[violation.field] = violation.message.trim().slice(0, 160);
        }
      }
    }
    return new RegistrationError('Revise os campos informados.', { fieldErrors, cause: error });
  }

  if (code === 'DATABASE_BUSY') {
    return new RegistrationError('O cadastro está temporariamente indisponível. Tente novamente.', {
      cause: error,
    });
  }

  if (!error?.response) {
    return new RegistrationError('Não foi possível conectar ao serviço. Verifique sua conexão e tente novamente.', {
      cause: error,
    });
  }

  return new RegistrationError('Não foi possível concluir o cadastro. Tente novamente.', { cause: error });
}
