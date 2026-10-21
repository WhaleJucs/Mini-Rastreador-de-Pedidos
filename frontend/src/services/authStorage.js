const STORAGE_PREFIX = 'foody.delivery.auth';

export function authStorageKey(appEnv) {
  if (!['local', 'hml', 'prod'].includes(appEnv)) {
    throw new Error('Ambiente inválido para a sessão.');
  }
  return `${STORAGE_PREFIX}.${appEnv}.v1`;
}

function browserStorage() {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null;
  }
}

export function createAuthStorage(appEnv, options = {}) {
  const key = authStorageKey(appEnv);
  const resolveStorage = Object.hasOwn(options, 'storage')
    ? () => options.storage
    : browserStorage;

  function clear() {
    try {
      resolveStorage()?.removeItem(key);
      return true;
    } catch {
      return false;
    }
  }

  function read() {
    try {
      const value = resolveStorage()?.getItem(key);
      if (!value) return null;
      return JSON.parse(value);
    } catch {
      clear();
      return null;
    }
  }

  function write(session) {
    try {
      resolveStorage()?.setItem(key, JSON.stringify(session));
      return resolveStorage() !== null;
    } catch {
      return false;
    }
  }

  return Object.freeze({ key, read, write, clear });
}
