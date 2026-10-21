import assert from 'node:assert/strict';
import test from 'node:test';
import { registerAndSignIn, RegistrationSignInError } from './registrationSession.js';

const user = { name: 'Pessoa', email: ' Pessoa@Example.invalid ', password: '  senha com espaços  ' };
const registered = { id: 7, name: 'Pessoa', email: 'pessoa@example.invalid' };

function deferred() {
  let resolve;
  const promise = new Promise(yes => { resolve = yes; });
  return { promise, resolve };
}

test('waits for registration before logging in with the registered email and unchanged password', async () => {
  const pending = deferred();
  const calls = [];
  const session = { user: registered };
  const controller = new AbortController();
  const result = registerAndSignIn({
    register: (input, options) => { calls.push({ kind: 'register', input, options }); return pending.promise; },
    login: async (input, options) => { calls.push({ kind: 'login', input, options }); return session; },
    isCurrent: () => true,
  }, user, { signal: controller.signal });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].input, user);
  pending.resolve(registered);
  assert.equal(await result, session);
  assert.equal(calls.length, 2);
  assert.deepEqual(calls[1].input, { email: registered.email, password: user.password });
  assert.equal(calls[0].options.signal, controller.signal);
  assert.equal(calls[1].options.signal, controller.signal);
});

test('failed registration keeps its field errors and never starts login', async () => {
  const error = { message: 'E-mail em uso', fieldErrors: { email: 'Já cadastrado' } };
  let logins = 0;
  await assert.rejects(registerAndSignIn({
    register: async () => { throw error; },
    login: async () => { logins += 1; },
    isCurrent: () => true,
  }, user), failure => failure === error);
  assert.equal(logins, 0);
});

test('login failure after registration reports the created account without repeating either request', async () => {
  let registrations = 0;
  let logins = 0;
  await assert.rejects(registerAndSignIn({
    register: async () => { registrations += 1; return registered; },
    login: async () => { logins += 1; throw new Error('private server details'); },
    isCurrent: () => true,
  }, user), error => {
    assert.ok(error instanceof RegistrationSignInError);
    assert.equal(error.registrationCompleted, true);
    assert.match(error.message, /conta foi criada/);
    assert.doesNotMatch(error.message, /private|server details/);
    return true;
  });
  assert.equal(registrations, 1);
  assert.equal(logins, 1);
});

test('an already ended context starts neither request', async () => {
  let requests = 0;
  await assert.rejects(registerAndSignIn({
    register: async () => { requests += 1; },
    login: async () => { requests += 1; },
    isCurrent: () => false,
  }, user), { code: 'ERR_CANCELED' });
  assert.equal(requests, 0);
});

for (const reason of ['abort', 'session-change']) {
  test(`${reason} while registration is pending cannot establish a session later`, async () => {
    const pending = deferred();
    const controller = new AbortController();
    let current = true;
    let logins = 0;
    const result = registerAndSignIn({
      register: () => pending.promise,
      login: async () => { logins += 1; },
      isCurrent: () => current,
    }, user, { signal: controller.signal });
    if (reason === 'abort') controller.abort();
    else current = false;
    pending.resolve(registered);
    await assert.rejects(result, { code: 'ERR_CANCELED' });
    assert.equal(logins, 0);
  });
}

test('cancelled login does not become a fallback redirect after cleanup', async () => {
  await assert.rejects(registerAndSignIn({
    register: async () => registered,
    login: async () => { throw { code: 'ERR_CANCELED' }; },
    isCurrent: () => true,
  }, user), { code: 'ERR_CANCELED' });
});
