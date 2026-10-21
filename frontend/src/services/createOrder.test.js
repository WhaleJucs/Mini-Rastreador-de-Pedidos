import assert from 'node:assert/strict';
import test from 'node:test';
import { createdOrderResultFrom, OrdersResponseError } from './orders.js';
import { createOrderErrorFrom, createOrderSender, orderFieldErrorsForForm } from './createOrder.js';

function orderFixture() {
  return {
    id: 8,
    customerName: 'Destinatário',
    deliveryAddress: { street: 'Rua de Teste', number: 'S/N', city: 'Cidade', state: 'SP', zipCode: null },
    items: [{ id: 12, productName: 'Produto', quantity: 2 }],
    status: 'RECEBIDO',
    createdAt: '2026-10-21T12:00:00Z',
    version: 0,
  };
}

function senderFixture() {
  const requests = [];
  const snapshots = [];
  const successes = [];
  const sender = createOrderSender({
    send: (payload, { signal }) => new Promise((resolve, reject) => {
      requests.push({ payload, signal, resolve, reject });
    }),
    onChange: state => snapshots.push(state),
    onSuccess: order => successes.push(order),
  });
  return { sender, requests, snapshots, successes };
}

test('accepts creation only for HTTP 201 and a complete DTO with server creation metadata', () => {
  const order = orderFixture();
  assert.deepEqual(createdOrderResultFrom({ status: 201, data: order }), order);
  for (const status of [200, 202, 204]) {
    assert.throws(() => createdOrderResultFrom({ status, data: order }), OrdersResponseError);
  }
  for (const data of [null, '<html>SPA</html>', { id: 8 },
    { ...order, id: 0 }, { ...order, status: 'ENTREGUE' }, { ...order, version: 1 }]) {
    assert.throws(() => createdOrderResultFrom({ status: 201, data }), OrdersResponseError);
  }
});

test('maps only approved server fields to stable form rows without exposing server details', () => {
  const error = createOrderErrorFrom({
    response: { status: 400, data: { code: 'VALIDATION_ERROR', detail: 'SQL privado', violations: [
      { field: 'customerName', message: 'detalhe privado' },
      { field: 'deliveryAddress.street', message: '<b>não renderizar</b>' },
      { field: 'items[1].quantity', message: 'stack trace' },
      { field: 'items[99].productName', message: 'fora da lista' },
      { field: '__proto__', message: 'não permitido' },
      { field: 'phone', message: 'campo não aprovado' },
    ] } },
  });
  const fields = orderFieldErrorsForForm(error.fieldErrors, [{ rowId: 'item-1' }, { rowId: 'item-3' }]);
  assert.deepEqual(Object.keys(fields), ['customerName', 'deliveryAddress.street', 'items.item-3.quantity']);
  assert.equal(error.uncertain, false);
  assert.doesNotMatch(JSON.stringify({ message: error.message, fields }), /SQL|privado|stack|<b>|phone/);
});

test('classifies lost, invalid or unexpected responses as uncertain and busy database as retryable', () => {
  for (const error of [
    new Error('offline'),
    { code: 'ECONNABORTED' },
    new OrdersResponseError(),
    { response: { status: 500 } },
    { response: { status: 503, data: '<html>proxy</html>' } },
  ]) {
    const failure = createOrderErrorFrom(error);
    assert.equal(failure.uncertain, true);
    assert.match(failure.message, /Confira a lista de pedidos/);
  }
  assert.equal(createOrderErrorFrom({ response: { status: 401 } }).uncertain, false);
  const busy = createOrderErrorFrom({ response: { status: 503, data: { code: 'DATABASE_BUSY' } } });
  assert.equal(busy.uncertain, false);
  assert.match(busy.message, /temporariamente ocupado/);
});

test('a synchronous in-flight guard permits only one request and blocks repeats after confirmation', async () => {
  const { sender, requests, snapshots, successes } = senderFixture();
  const payload = { customerName: 'Destinatário' };
  const first = sender.submit(payload);
  const duplicate = sender.submit(payload);

  assert.equal(requests.length, 1);
  assert.equal(await duplicate, null);
  assert.deepEqual(snapshots.at(-1), { isSubmitting: true, error: null });
  assert.equal(requests[0].payload, payload);
  const order = orderFixture();
  requests[0].resolve(order);
  assert.deepEqual(await first, { order });
  assert.deepEqual(successes, [order]);
  assert.deepEqual(snapshots.at(-1), { isSubmitting: false, error: null });
  assert.equal(await sender.submit(payload), null);
  assert.equal(requests.length, 1);
  sender.dispose();
});

test('does not retry an uncertain mutation and keeps the uncertain error when fields are edited', async () => {
  const { sender, requests, snapshots, successes } = senderFixture();
  const payload = { customerName: 'Destinatário' };
  const pending = sender.submit(payload);
  requests[0].reject({ code: 'ECONNABORTED' });
  const result = await pending;
  assert.equal(result.error.uncertain, true);
  const state = snapshots.at(-1);
  sender.clearError();
  assert.equal(snapshots.at(-1), state);
  assert.equal(await sender.submit(payload), null);
  assert.equal(requests.length, 1);
  assert.equal(successes.length, 0);
  sender.dispose();
});

test('allows a manual retry after a known rejection, without retrying automatically', async () => {
  for (const response of [
    { status: 400, data: { code: 'VALIDATION_ERROR', violations: [] } },
    { status: 503, data: { code: 'DATABASE_BUSY' } },
  ]) {
    const { sender, requests, snapshots, successes } = senderFixture();
    const payload = { customerName: 'Destinatário' };
    const first = sender.submit(payload);
    requests[0].reject({ response });
    assert.equal((await first).error.uncertain, false);
    assert.equal(requests.length, 1);
    sender.clearError();
    assert.deepEqual(snapshots.at(-1), { isSubmitting: false, error: null });

    const retry = sender.submit(payload);
    assert.equal(requests.length, 2);
    requests[1].resolve(orderFixture());
    await retry;
    assert.equal(successes.length, 1);
    sender.dispose();
  }
});

test('a structurally invalid success never navigates or unlocks a blind resend', async () => {
  const snapshots = [];
  const successes = [];
  let count = 0;
  const sender = createOrderSender({
    send: async () => {
      count += 1;
      return createdOrderResultFrom({ status: 201, data: { id: 8 } });
    },
    onChange: state => snapshots.push(state),
    onSuccess: order => successes.push(order),
  });
  assert.equal((await sender.submit({})).error.uncertain, true);
  assert.equal(await sender.submit({}), null);
  assert.equal(count, 1);
  assert.equal(successes.length, 0);
  assert.equal(snapshots.at(-1).isSubmitting, false);
  sender.dispose();
});

test('disposal cancels the request and discards late success or failure from an ended context', async () => {
  for (const shouldFail of [false, true]) {
    const { sender, requests, snapshots, successes } = senderFixture();
    const pending = sender.submit({});
    sender.dispose();
    assert.equal(requests[0].signal.aborted, true);
    const count = snapshots.length;
    if (shouldFail) requests[0].reject(new Error('resposta antiga'));
    else requests[0].resolve(orderFixture());
    assert.equal(await pending, null);
    assert.equal(snapshots.length, count);
    assert.equal(successes.length, 0);
    assert.equal(await sender.submit({}), null);
    assert.equal(requests.length, 1);
  }
});

test('a new sender can submit after cleanup without accepting the previous session result', async () => {
  const previous = senderFixture();
  const oldRequest = previous.sender.submit({});
  previous.sender.dispose();
  const current = senderFixture();
  const newRequest = current.sender.submit({});
  current.requests[0].resolve(orderFixture());
  await newRequest;
  previous.requests[0].resolve(orderFixture());
  await oldRequest;
  assert.equal(current.successes.length, 1);
  assert.equal(previous.successes.length, 0);
  current.sender.dispose();
});
