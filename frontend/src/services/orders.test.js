import assert from 'node:assert/strict';
import test from 'node:test';
import { OrdersResponseError, ordersErrorMessage, ordersResultFrom } from './orders.js';

function orderFixture() {
  return {
    id: 7,
    customerName: 'Destinatário de teste',
    deliveryAddress: { street: 'Rua de Teste', number: 'S/N', city: 'Cidade', state: 'SP', zipCode: null },
    items: [
      { id: 10, productName: 'Produto A', quantity: 1 },
      { id: 11, productName: 'Produto B', quantity: 2 },
    ],
    status: 'RECEBIDO',
    createdAt: '2026-10-21T12:00:00.000000001Z',
    version: 0,
  };
}

test('accepts an empty collection only as an HTTP 200 array', () => {
  assert.deepEqual(ordersResultFrom({ status: 200, data: [] }), []);
  for (const response of [
    { status: 201, data: [] },
    { status: 200, data: { content: [] } },
    { status: 200, data: '<html>SPA</html>' },
    { status: 200, data: null },
  ]) {
    assert.throws(() => ordersResultFrom(response), OrdersResponseError);
  }
});

test('preserves response order, item identities, metadata and optional zip code', () => {
  const first = orderFixture();
  first.status = 'ENTREGUE';
  first.version = 4;
  first.deliveryAddress.zipCode = '00000000';
  const second = orderFixture();
  second.id = 6;
  assert.deepEqual(ordersResultFrom({ status: 200, data: [first, second] }), [first, second]);
});

test('copies only the approved response fields without mutating input', () => {
  const source = orderFixture();
  source.userId = 9;
  source.password = 'não transportar';
  source.deliveryAddress.bairro = 'não transportar';
  source.items[0].price = 10;
  const original = structuredClone(source);

  assert.deepEqual(ordersResultFrom({ status: 200, data: [source] }), [orderFixture()]);
  assert.deepEqual(source, original);
});

test('rejects malformed orders rather than silently omitting rows or displaying an empty collection', () => {
  const invalidChanges = [
    order => { order.id = '7'; },
    order => { order.id = Number.MAX_SAFE_INTEGER + 1; },
    order => { order.customerName = '   '; },
    order => { order.status = 'DESCONHECIDO'; },
    order => { order.version = -1; },
    order => { order.version = '0'; },
    order => { order.createdAt = 'data inválida'; },
    order => { order.deliveryAddress = null; },
    order => { order.deliveryAddress.number = ''; },
    order => { order.deliveryAddress.state = 'sp'; },
    order => { order.deliveryAddress.zipCode = '00000-000'; },
    order => { delete order.deliveryAddress.zipCode; },
    order => { order.items = []; },
    order => { order.items = [null]; },
    order => { order.items[0].productName = '   '; },
    order => { order.items[0].quantity = 1.5; },
    order => { order.items[0].quantity = '2'; },
    order => { order.items[0].quantity = 0; },
    order => { order.items[0].quantity = 2_147_483_648; },
    order => { order.items[1].id = order.items[0].id; },
  ];
  for (const change of invalidChanges) {
    const invalid = orderFixture();
    change(invalid);
    assert.throws(() => ordersResultFrom({ status: 200, data: [invalid] }), OrdersResponseError);
  }
  assert.throws(() => ordersResultFrom({ status: 200, data: [null] }), OrdersResponseError);
  assert.throws(() => ordersResultFrom({ status: 200, data: [orderFixture(), orderFixture()] }), OrdersResponseError);
});

test('maps network, authentication, busy database and malformed responses to controlled messages', () => {
  assert.match(ordersErrorMessage(new Error('detalhe privado')), /conexão/);
  assert.match(ordersErrorMessage({ response: { status: 401 } }), /sessão expirou/);
  assert.match(ordersErrorMessage({ response: { status: 503 } }), /temporariamente ocupado/);
  assert.match(ordersErrorMessage(new OrdersResponseError()), /resposta de pedidos inválida/);
  const message = ordersErrorMessage({
    response: { status: 500, data: { detail: '<b>SQL e segredo</b>', message: 'stack trace' } },
  });
  assert.doesNotMatch(message, /SQL|segredo|stack|<b>/);
});
