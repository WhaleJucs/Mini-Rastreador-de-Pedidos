import assert from 'node:assert/strict';
import test from 'node:test';
import { createAuthStorage, authStorageKey } from './authStorage.js';
import { isUsableSession, restoreSession, sessionFromLogin, tokenExpirationMs } from './authSession.js';

const NOW = Date.parse('2026-10-20T12:00:00Z');

function token(expiresAt) {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ sub: '1', exp: expiresAt / 1000 })}.signature`;
}

function loginResponse(expiresAt = NOW + 3_600_000) {
  return {
    accessToken: token(expiresAt),
    tokenType: 'Bearer',
    expiresIn: 3600,
    user: { id: 1, name: 'Pessoa Teste', email: 'pessoa@example.invalid', password: 'não persistir' },
    password: 'não persistir',
  };
}

function memoryStorage() {
  const values = new Map();
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key),
    values,
  };
}

test('namespaces local, hml and prod sessions independently', () => {
  assert.equal(authStorageKey('local'), 'foody.delivery.auth.local.v1');
  assert.equal(authStorageKey('hml'), 'foody.delivery.auth.hml.v1');
  assert.equal(authStorageKey('prod'), 'foody.delivery.auth.prod.v1');
  assert.throws(() => authStorageKey('unknown'), /Ambiente inválido/);
});

test('persists only the minimal session and restores it while the token is valid', () => {
  const target = memoryStorage();
  const storage = createAuthStorage('local', { storage: target });
  const session = sessionFromLogin(loginResponse(), NOW);

  assert.deepEqual(session, {
    accessToken: loginResponse().accessToken,
    tokenType: 'Bearer',
    expiresAt: NOW + 3_600_000,
    user: { id: 1, name: 'Pessoa Teste', email: 'pessoa@example.invalid' },
  });
  assert.equal(storage.write(session), true);
  assert.deepEqual(restoreSession(storage, NOW), session);
  assert.doesNotMatch(target.values.get(storage.key), /password|não persistir/);
});

test('clears malformed, inconsistent and expired persisted sessions', () => {
  const target = memoryStorage();
  const storage = createAuthStorage('local', { storage: target });

  target.setItem(storage.key, '{broken');
  assert.equal(restoreSession(storage, NOW), null);
  assert.equal(target.getItem(storage.key), null);

  const session = sessionFromLogin(loginResponse(), NOW);
  target.setItem(storage.key, JSON.stringify({ ...session, expiresAt: session.expiresAt + 1_000 }));
  assert.equal(restoreSession(storage, NOW), null);

  target.setItem(storage.key, JSON.stringify(session));
  assert.equal(restoreSession(storage, session.expiresAt), null);
  assert.equal(target.getItem(storage.key), null);
});

test('handles unavailable storage without crashing and keeps validation local to UX', () => {
  const blocked = {
    getItem: () => { throw new Error('blocked'); },
    setItem: () => { throw new Error('blocked'); },
    removeItem: () => { throw new Error('blocked'); },
  };
  const storage = createAuthStorage('local', { storage: blocked });
  const session = sessionFromLogin(loginResponse(), NOW);

  assert.equal(storage.read(), null);
  assert.equal(storage.write(session), false);
  assert.equal(storage.clear(), false);
  assert.equal(restoreSession(storage, NOW), null);
  assert.equal(isUsableSession(session, NOW), true);
  assert.equal(tokenExpirationMs('not-a-token'), null);
});

test('rejects malformed login responses and expired tokens', () => {
  assert.throws(() => sessionFromLogin({ ...loginResponse(), tokenType: 'Basic' }, NOW), /Resposta/);
  assert.throws(() => sessionFromLogin(loginResponse(NOW), NOW), /expirado/);
});
