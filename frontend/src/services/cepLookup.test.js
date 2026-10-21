import assert from 'node:assert/strict';
import test from 'node:test';
import { lookupCep, CepLookupError } from './cepApi.js';
import { createCepLookup } from './cepLookup.js';
import { normalizeZipCode } from '../utils/zipCode.js';

const RESULT = { zipCode: '00000000', street: 'Rua Simulada', city: 'Cidade Simulada', state: 'SP' };

function fixture() {
  const address = { zipCode: '00000-000', street: 'Rua manual', city: 'Cidade manual', state: 'RJ', number: 'S/N' };
  const requests = [];
  const snapshots = [];
  const patches = [];
  const timers = new Map();
  let nextTimer = 0;
  const lookup = createCepLookup({
    getAddress: () => address,
    load: (zipCode, { signal }) => new Promise((resolve, reject) => requests.push({ zipCode, signal, resolve, reject })),
    onApply: patch => { patches.push(patch); Object.assign(address, patch); },
    onChange: state => snapshots.push(state),
    setTimer: callback => { const id = ++nextTimer; timers.set(id, callback); return id; },
    clearTimer: id => timers.delete(id),
  });
  return { address, requests, snapshots, patches, timers, lookup };
}

test('validates CEP without concealing invalid characters', () => {
  assert.equal(normalizeZipCode(' 00000-000 '), '00000000');
  for (const value of ['', '0000000', '000000000', 'abcdefgh', '0000-0000', null]) {
    assert.equal(normalizeZipCode(value), null);
  }
});

test('uses only CEP in HTTPS request without Authorization or cookies and maps only approved fields', async () => {
  const controller = new AbortController();
  let request;
  const result = await lookupCep('00000-000', { signal: controller.signal, fetcher: async (url, options) => {
    request = { url, options };
    return { ok: true, json: async () => ({ cep: '00000-000', logradouro: 'Rua', localidade: 'Cidade', uf: 'sp', bairro: 'ignorar', complemento: 'ignorar' }) };
  } });
  assert.equal(request.url, 'https://viacep.com.br/ws/00000000/json/');
  assert.deepEqual(request.options.headers, { Accept: 'application/json' });
  assert.equal(request.options.credentials, 'omit');
  assert.equal(request.options.signal, controller.signal);
  assert.deepEqual(result, { zipCode: '00000000', street: 'Rua', city: 'Cidade', state: 'SP' });
});

test('rejects nonexistent, unexpected, malformed and failed responses without trusting remote messages', async () => {
  for (const data of [{ erro: true }, { erro: 'true' }, {}, null, '<html>erro</html>',
    { cep: '11111-111', logradouro: 'Rua', localidade: 'Cidade', uf: 'SP' }]) {
    await assert.rejects(lookupCep('00000000', { fetcher: async () => ({ ok: true, json: async () => data }) }), CepLookupError);
  }
  await assert.rejects(lookupCep('00000000', { fetcher: async () => ({ ok: false }) }), /consultar/);
  await assert.rejects(lookupCep('00000000', { fetcher: async () => ({ ok: true, json: async () => { throw new Error('HTML privado'); } }) }), /resposta inválida/);
  let calls = 0;
  await assert.rejects(lookupCep('inválido', { fetcher: async () => { calls += 1; } }), /oito dígitos/);
  assert.equal(calls, 0);
});

test('looks up only on demand and prevents duplicate searches while preserving number', async () => {
  const f = fixture();
  assert.equal(f.requests.length, 0);
  const pending = f.lookup.lookup();
  await f.lookup.lookup();
  assert.equal(f.requests.length, 1);
  f.requests[0].resolve(RESULT);
  await pending;
  assert.equal(f.address.number, 'S/N');
  assert.equal(f.address.street, RESULT.street);
  assert.equal(f.snapshots.at(-1).isLoading, false);
  assert.equal(f.timers.size, 0);
  f.lookup.dispose();
});

test('preserves a field edited during search even if it was edited back to its original value', async () => {
  const f = fixture();
  const pending = f.lookup.lookup();
  f.lookup.edit('street');
  f.address.street = 'Outra rua';
  f.lookup.edit('street');
  f.address.street = 'Rua manual';
  f.requests[0].resolve(RESULT);
  await pending;
  assert.equal(f.address.street, 'Rua manual');
  assert.equal(f.address.city, RESULT.city);
  assert.equal(f.address.number, 'S/N');
  f.lookup.dispose();
});

test('does not erase manual fields when ViaCEP returns empty values', async () => {
  const f = fixture();
  const pending = f.lookup.lookup();
  f.requests[0].resolve({ ...RESULT, street: '', city: '', state: '' });
  await pending;
  assert.equal(f.address.street, 'Rua manual');
  assert.equal(f.address.city, 'Cidade manual');
  assert.equal(f.address.state, 'RJ');
  f.lookup.dispose();
});

test('CEP changes cancel old lookup and old responses cannot end a newer loading state', async () => {
  const f = fixture();
  const old = f.lookup.lookup();
  f.lookup.edit('zipCode');
  f.address.zipCode = '11111-111';
  const current = f.lookup.lookup();
  assert.equal(f.requests[0].signal.aborted, true);
  const count = f.snapshots.length;
  f.requests[0].resolve(RESULT);
  await old;
  assert.equal(f.snapshots.length, count);
  assert.equal(f.snapshots.at(-1).isLoading, true);
  f.requests[1].resolve({ ...RESULT, zipCode: '11111111' });
  await current;
  assert.equal(f.patches.length, 1);
  f.lookup.dispose();
});

test('offline and timeout preserve manual data and stop loading without a retry loop', async () => {
  const f = fixture();
  const offline = f.lookup.lookup();
  f.requests[0].reject(new Error('detalhe remoto privado'));
  await offline;
  assert.equal(f.address.street, 'Rua manual');
  assert.equal(f.snapshots.at(-1).isLoading, false);
  assert.doesNotMatch(f.snapshots.at(-1).error, /privado/);
  const timeout = f.lookup.lookup();
  [...f.timers.values()][0]();
  assert.equal(f.snapshots.at(-1).isLoading, false);
  assert.match(f.snapshots.at(-1).error, /demorou/);
  f.requests[1].resolve(RESULT);
  await timeout;
  assert.equal(f.patches.length, 0);
  assert.equal(f.requests.length, 2);
  f.lookup.dispose();
});

test('cleanup or submit cancellation discards late responses and a recreated lookup remains usable', async () => {
  for (const action of ['cancel', 'dispose']) {
    const f = fixture();
    const pending = f.lookup.lookup();
    f.lookup[action]();
    const count = f.snapshots.length;
    f.requests[0].resolve(RESULT);
    await pending;
    assert.equal(f.patches.length, 0);
    assert.equal(f.snapshots.length, count);
    assert.equal(f.requests[0].signal.aborted, true);
    f.lookup.dispose();
  }
  const next = fixture();
  const pending = next.lookup.lookup();
  next.requests[0].resolve(RESULT);
  await pending;
  assert.equal(next.patches.length, 1);
  next.lookup.dispose();
});
