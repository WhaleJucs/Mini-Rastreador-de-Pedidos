import assert from 'node:assert/strict';
import test from 'node:test';
import axios, { AxiosError, CanceledError } from 'axios';
import { installApiSessionInterceptors } from './apiSession.js';

const NOW = Date.parse('2026-10-20T12:00:00Z');
const SESSION = {
  accessToken: `${Buffer.from('{}').toString('base64url')}.${Buffer.from(JSON.stringify({ exp: (NOW + 3_600_000) / 1000 })).toString('base64url')}.signature`,
  tokenType: 'Bearer',
  expiresAt: NOW + 3_600_000,
  user: { id: 1, name: 'Pessoa Teste', email: 'pessoa@example.invalid' },
};

function response(config, status = 200) {
  return { config, status, statusText: String(status), headers: {}, data: {}, request: {} };
}

function bridge() {
  let generation = 0;
  let session = SESSION;
  const endings = [];
  return {
    getSession: () => session,
    getGeneration: () => generation,
    endSession: (reason, expected) => {
      if (expected !== undefined && expected !== generation) return false;
      endings.push(reason);
      generation += 1;
      session = null;
      return true;
    },
    now: () => NOW,
    endings,
    advance: () => { generation += 1; },
    expire: () => { session = { ...SESSION, expiresAt: NOW }; },
  };
}

test('adds Bearer only to the configured API instance and skips public auth calls', async () => {
  const seen = [];
  const adapter = async config => {
    seen.push(config.headers.get('Authorization'));
    return response(config);
  };
  const api = axios.create({ adapter });
  const external = axios.create({ adapter });
  const state = bridge();
  const connection = installApiSessionInterceptors(api, state);

  await api.get('/orders');
  await api.post('/auth/login', {}, { skipAuth: true });
  await external.get('https://external.example.invalid/address');

  assert.deepEqual(seen, [`Bearer ${SESSION.accessToken}`, undefined, undefined]);
  connection.disconnect();
});

test('ends the current session on 401 but preserves it on network failure', async () => {
  const state = bridge();
  const api = axios.create({ adapter: async config => {
    if (config.url === '/offline') throw new AxiosError('offline', AxiosError.ERR_NETWORK, config);
    const result = response(config, 401);
    throw new AxiosError('unauthorized', AxiosError.ERR_BAD_REQUEST, config, {}, result);
  } });
  const connection = installApiSessionInterceptors(api, state);

  await assert.rejects(api.get('/offline'), error => error.code === AxiosError.ERR_NETWORK);
  assert.deepEqual(state.endings, []);
  await assert.rejects(api.get('/orders'), error => error.response?.status === 401);
  assert.deepEqual(state.endings, ['unauthorized']);
  connection.disconnect();
});

test('does not end an existing session for a public login 401', async () => {
  const state = bridge();
  const api = axios.create({ adapter: async config => {
    const result = response(config, 401);
    throw new AxiosError('unauthorized', AxiosError.ERR_BAD_REQUEST, config, {}, result);
  } });
  const connection = installApiSessionInterceptors(api, state);

  await assert.rejects(api.post('/auth/login', {}, { skipAuth: true }), error => error.response?.status === 401);
  assert.deepEqual(state.endings, []);
  connection.disconnect();
});

test('rejects an expired session before sending the request', async () => {
  let calls = 0;
  const state = bridge();
  state.expire();
  const api = axios.create({ adapter: async config => {
    calls += 1;
    return response(config);
  } });
  const connection = installApiSessionInterceptors(api, state);

  await assert.rejects(api.get('/orders'), error => error instanceof CanceledError);
  assert.equal(calls, 0);
  assert.deepEqual(state.endings, ['expired']);
  connection.disconnect();
});

test('discards a response from an older session generation', async () => {
  let resolve;
  const delayed = new Promise(done => { resolve = done; });
  const state = bridge();
  const api = axios.create({ adapter: config => delayed.then(() => response(config)) });
  const connection = installApiSessionInterceptors(api, state);

  const request = api.get('/orders');
  await new Promise(setImmediate);
  state.advance();
  resolve();

  await assert.rejects(request, error => error instanceof CanceledError);
  connection.disconnect();
});

test('cleanup ejects interceptors and aborts pending API requests', async () => {
  let started;
  const adapterStarted = new Promise(resolve => { started = resolve; });
  const state = bridge();
  const api = axios.create({ adapter: config => new Promise((resolve, reject) => {
    started();
    config.signal.addEventListener('abort', () => reject(new CanceledError('aborted', config)), { once: true });
  }) });
  const connection = installApiSessionInterceptors(api, state);

  const request = api.get('/slow');
  await adapterStarted;
  connection.cancelPending();

  await assert.rejects(request, error => error instanceof CanceledError);
  connection.disconnect();
  assert.equal(api.interceptors.request.handlers.filter(Boolean).length, 0);
  assert.equal(api.interceptors.response.handlers.filter(Boolean).length, 0);
});
