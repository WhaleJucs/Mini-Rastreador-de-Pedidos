import { CanceledError } from 'axios';

export class RegistrationSignInError extends Error {
  constructor() {
    super('Sua conta foi criada, mas não foi possível entrar automaticamente. Entre com sua conta.');
    this.name = 'RegistrationSignInError';
    this.registrationCompleted = true;
  }
}

export async function registerAndSignIn({ register, login, isCurrent }, user, { signal } = {}) {
  if (signal?.aborted || !isCurrent()) throw new CanceledError('Cadastro interrompido.');
  const registeredUser = await register(user, { signal });
  if (signal?.aborted || !isCurrent()) throw new CanceledError('Cadastro interrompido.');

  try {
    return await login({ email: registeredUser.email, password: user.password }, { signal });
  } catch (error) {
    if (error?.code === 'ERR_CANCELED' || signal?.aborted) {
      throw new CanceledError('Entrada automática interrompida.');
    }
    throw new RegistrationSignInError();
  }
}
