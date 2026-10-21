import assert from 'node:assert/strict';
import test from 'node:test';
import { loginErrorFrom, loginResultFrom, validateLogin } from './login.js';

test('validates login fields without changing the password', () => {
  const password = ' senha exata ';
  assert.deepEqual(validateLogin({ email: ' pessoa@example.com ', password }), {});
  assert.equal(password, ' senha exata ');
  assert.deepEqual(validateLogin({ email: 'invalido', password: '   ' }), {
    email: 'Informe um e-mail válido.',
    password: 'Informe sua senha.',
  });
  assert.match(validateLogin({ email: 'pessoa@example.com', password: 'á'.repeat(37) }).password, /72 bytes/);
});

test('accepts only an HTTP 200 login response', () => {
  const data = { accessToken: 'token', tokenType: 'Bearer', expiresIn: 3600, user: { id: 1 } };
  assert.equal(loginResultFrom({ status: 200, data }), data);
  assert.throws(() => loginResultFrom({ status: 201, data }), /resposta inválida/);
  assert.throws(() => loginResultFrom({ status: 200, data: null }), /resposta inválida/);
});

test('maps login failures to controlled messages', () => {
  const invalid = loginErrorFrom({ response: { data: { code: 'INVALID_CREDENTIALS', detail: '<b>segredo</b>' } } });
  assert.equal(invalid.message, 'E-mail ou senha inválidos.');
  assert.doesNotMatch(invalid.message, /segredo|<b>/);

  const validation = loginErrorFrom({
    response: { data: { code: 'VALIDATION_ERROR', violations: [
      { field: 'email', message: 'deve ser válido' },
      { field: 'ignored', message: 'não renderizar' },
    ] } },
  });
  assert.deepEqual(validation.fieldErrors, { email: 'deve ser válido' });
  assert.match(loginErrorFrom(new Error('offline')).message, /conectar/);
  assert.match(loginErrorFrom({ response: { data: '<html>erro</html>' } }).message, /Não foi possível entrar/);
});
