import assert from 'node:assert/strict';
import test from 'node:test';
import { registrationErrorFrom, registrationResultFrom, validateRegistration } from './registration.js';

test('validates the registration contract without changing the password', () => {
  const password = '  segredo com espaços  ';

  assert.deepEqual(validateRegistration({
    name: `  ${'P'.repeat(100)}  `,
    email: ' pessoa@example.invalid ',
    password,
  }), {});
  assert.equal(password, '  segredo com espaços  ');
  assert.equal(validateRegistration({ name: ' ', email: 'inválido', password: ' ' }).name, 'Informe seu nome.');
  assert.equal(validateRegistration({ name: 'Pessoa', email: 'inválido', password: 'senha' }).email, 'Informe um e-mail válido.');
  assert.deepEqual(validateRegistration({ name: 'Pessoa', email: ' pessoa@localhost ', password: 'senha' }), {});
  assert.equal(validateRegistration({ name: 'Pessoa', email: 'pessoa@example.invalid', password: '🔐'.repeat(19) }).password,
    'A senha deve ter no máximo 72 bytes em UTF-8.');
});

test('maps duplicate and field validation problems to controlled feedback', () => {
  const duplicate = registrationErrorFrom({
    response: { data: { code: 'EMAIL_ALREADY_EXISTS', detail: '<strong>SQL</strong>' } },
  });
  assert.deepEqual(duplicate.fieldErrors, { email: 'Este e-mail já está cadastrado.' });
  assert.doesNotMatch(duplicate.message, /SQL|strong/);

  const invalid = registrationErrorFrom({
    response: {
      data: {
        code: 'VALIDATION_ERROR',
        violations: [
          { field: 'email', message: 'deve ser um e-mail válido' },
          { field: 'unknown', message: 'não mostrar' },
        ],
      },
    },
  });
  assert.deepEqual(invalid.fieldErrors, { email: 'deve ser um e-mail válido' });
});

test('accepts only a 201 registration response and keeps the public DTO fields', () => {
  const response = {
    status: 201,
    data: { id: 7, name: 'Pessoa', email: 'pessoa@example.invalid', passwordHash: 'não devolver' },
  };

  assert.deepEqual(registrationResultFrom(response), {
    id: 7,
    name: 'Pessoa',
    email: 'pessoa@example.invalid',
  });
  assert.throws(() => registrationResultFrom({ ...response, status: 200 }), /resposta inválida/);
  assert.throws(() => registrationResultFrom({ status: 201, data: { id: 7 } }), /resposta inválida/);
});

test('uses generic messages for network, database and unexpected failures', () => {
  assert.match(registrationErrorFrom(new Error('ECONNREFUSED')).message, /conectar/);
  assert.match(registrationErrorFrom({ response: { data: { code: 'DATABASE_BUSY' } } }).message, /temporariamente/);
  assert.match(registrationErrorFrom({ response: { data: '<html>upstream</html>' } }).message, /Não foi possível concluir/);
});
