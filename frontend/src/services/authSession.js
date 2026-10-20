const TOKEN_TYPE = 'Bearer';

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function decodePayload(token) {
  const parts = token.split('.');
  if (parts.length !== 3 || !parts[1]) throw new Error('Token inválido.');
  const normalized = parts[1].replaceAll('-', '+').replaceAll('_', '/');
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=');
  const binary = globalThis.atob(padded);
  const bytes = Uint8Array.from(binary, character => character.charCodeAt(0));
  return JSON.parse(new TextDecoder().decode(bytes));
}

export function tokenExpirationMs(token) {
  try {
    const payload = decodePayload(token);
    if (!Number.isSafeInteger(payload.exp) || payload.exp <= 0) return null;
    return payload.exp * 1000;
  } catch {
    return null;
  }
}

function validUser(user) {
  return isObject(user)
    && Number.isSafeInteger(user.id)
    && user.id > 0
    && typeof user.name === 'string'
    && user.name.trim().length > 0
    && typeof user.email === 'string'
    && user.email.trim().length > 0;
}

export function isUsableSession(session, now = Date.now()) {
  if (!isObject(session)
      || typeof session.accessToken !== 'string'
      || session.accessToken.length === 0
      || session.tokenType !== TOKEN_TYPE
      || !Number.isSafeInteger(session.expiresAt)
      || !validUser(session.user)) return false;

  const tokenExpiry = tokenExpirationMs(session.accessToken);
  return tokenExpiry !== null && tokenExpiry === session.expiresAt && tokenExpiry > now;
}

export function sessionFromLogin(response, now = Date.now()) {
  if (!isObject(response)
      || response.tokenType !== TOKEN_TYPE
      || !Number.isSafeInteger(response.expiresIn)
      || response.expiresIn <= 0
      || !validUser(response.user)) {
    throw new Error('Resposta de autenticação inválida.');
  }

  const expiresAt = tokenExpirationMs(response.accessToken);
  if (expiresAt === null || expiresAt <= now) {
    throw new Error('Token de autenticação inválido ou expirado.');
  }

  return Object.freeze({
    accessToken: response.accessToken,
    tokenType: TOKEN_TYPE,
    expiresAt,
    user: Object.freeze({
      id: response.user.id,
      name: response.user.name,
      email: response.user.email,
    }),
  });
}

export function restoreSession(storage, now = Date.now()) {
  const candidate = storage.read();
  if (isUsableSession(candidate, now)) return candidate;
  storage.clear();
  return null;
}
