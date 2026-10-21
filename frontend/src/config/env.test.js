import assert from 'node:assert/strict';
import test from 'node:test';
import { resolveEnv } from './env-validation.js';

for (const [mode, appEnv] of [['development', 'local'], ['hml', 'hml'], ['production', 'prod']]) {
  const values = { MODE: mode, VITE_APP_ENV: appEnv, VITE_API_BASE_URL: 'https://api.example.invalid/api/' };
  test(`resolves ${mode}/${appEnv} without depending on NODE_ENV`, () => {
    assert.deepEqual(resolveEnv({ ...values, NODE_ENV: 'other' }), {
      appEnv, apiBaseUrl: 'https://api.example.invalid/api',
    });
    assert.ok(Object.isFrozen(resolveEnv(values)));
  });
  const invalid = {
    'wrong label': { VITE_APP_ENV: 'invalid' },
    'credentials': { VITE_API_BASE_URL: 'https://user:password@api.example.invalid/api' },
    'query': { VITE_API_BASE_URL: 'https://api.example.invalid/api?secret=synthetic' },
    'duplicate base': { VITE_API_BASE_URL: 'https://api.example.invalid/api/api' },
  };
  if (appEnv !== 'local') {
    invalid['missing label'] = { VITE_APP_ENV: undefined };
    invalid['missing URL'] = { VITE_API_BASE_URL: '' };
  }
  for (const [name, override] of Object.entries(invalid)) {
    test(`${mode} rejects ${name} without echoing the input`, () => {
      assert.throws(() => resolveEnv({ ...values, ...override }), error =>
        error.message.startsWith('VITE_') && !error.message.includes('password') && !error.message.includes('synthetic'));
    });
  }
  if (appEnv !== 'local') {
    for (const url of ['/api', 'http://api.example.invalid/api', 'https://localhost/api',
      'https://127.0.0.1/api', 'https://[::1]/api']) {
      test(`${mode} rejects local/insecure endpoint ${url}`, () => {
        assert.throws(() => resolveEnv({ ...values, VITE_API_BASE_URL: url }));
      });
    }
  }
}

test('local accepts an explicit development proxy base', () => {
  assert.equal(resolveEnv({ MODE: 'development', VITE_APP_ENV: 'local', VITE_API_BASE_URL: '/api' }).apiBaseUrl, '/api');
});

test('local defaults to the development label and proxy without an env file', () => {
  assert.deepEqual(resolveEnv({ MODE: 'development' }), { appEnv: 'local', apiBaseUrl: '/api' });
});

for (const mode of ['local', 'unknown', undefined, 'toString', '__proto__']) {
  test(`rejects unsupported mode ${mode}`, () => {
    assert.throws(() => resolveEnv({ MODE: mode, VITE_APP_ENV: 'local', VITE_API_BASE_URL: '/api' }));
  });
}
