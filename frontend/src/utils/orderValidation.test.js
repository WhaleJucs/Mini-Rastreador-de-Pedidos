import assert from 'node:assert/strict';
import test from 'node:test';
import {
  OrderValidationError,
  createOrderItem,
  createOrderPayload,
  formatZipCodeInput,
  itemFieldName,
  validateOrder,
} from './orderValidation.js';

function formFixture() {
  return {
    customerName: ' Destinatário de teste ',
    deliveryAddress: { street: ' Rua de Teste ', number: ' S/N ', city: ' Cidade ', state: ' sp ', zipCode: '' },
    items: [
      { rowId: 'item-1', productName: ' Produto A ', quantity: '1' },
      { rowId: 'item-2', productName: 'Produto B', quantity: '2' },
    ],
  };
}

test('builds the approved payload with S/N and an optional zip code without changing form state', () => {
  const form = formFixture();
  const original = structuredClone(form);

  assert.deepEqual(validateOrder(form), {});
  assert.deepEqual(createOrderPayload(form), {
    customerName: 'Destinatário de teste',
    deliveryAddress: { street: 'Rua de Teste', number: 'S/N', city: 'Cidade', state: 'SP', zipCode: null },
    items: [{ productName: 'Produto A', quantity: 1 }, { productName: 'Produto B', quantity: 2 }],
  });
  assert.deepEqual(form, original);
});

test('does not send row identities, server metadata or unrelated fields', () => {
  const form = formFixture();
  form.status = 'ENTREGUE';
  form.version = 100;
  form.userId = 1;
  form.phone = 'não enviar';
  form.deliveryAddress.bairro = 'não enviar';
  form.items[0].price = 10;

  const payload = createOrderPayload(form);
  assert.deepEqual(Object.keys(payload), ['customerName', 'deliveryAddress', 'items']);
  assert.deepEqual(Object.keys(payload.deliveryAddress), ['street', 'number', 'city', 'state', 'zipCode']);
  assert.deepEqual(Object.keys(payload.items[0]), ['productName', 'quantity']);
});

test('rejects invalid quantities without rounding, truncating or turning blank input into zero', () => {
  for (const quantity of ['', '   ', '0', '-1', '1.5', '1,5', '1.0', '1e3', '0x10', 'Infinity', '2147483648']) {
    const form = formFixture();
    form.items[1].quantity = quantity;
    assert.ok(validateOrder(form)[itemFieldName('item-2', 'quantity')]);
    assert.throws(() => createOrderPayload(form), error => {
      assert.ok(error instanceof OrderValidationError);
      assert.ok(error.fieldErrors[itemFieldName('item-2', 'quantity')]);
      return true;
    });
  }
  const form = formFixture();
  form.items[0].quantity = '2147483647';
  assert.equal(createOrderPayload(form).items[0].quantity, 2_147_483_647);
});

test('validates every required field and accepts neither a missing list nor an empty list', () => {
  for (const field of ['customerName', 'street', 'number', 'city', 'state']) {
    const form = formFixture();
    const target = field === 'customerName' ? form : form.deliveryAddress;
    target[field] = '   ';
    const key = field === 'customerName' ? field : `deliveryAddress.${field}`;
    assert.ok(validateOrder(form)[key]);
  }
  const form = formFixture();
  form.items[0].productName = '';
  assert.ok(validateOrder(form)[itemFieldName('item-1', 'productName')]);
  for (const items of [undefined, null, []]) {
    form.items = items;
    assert.ok(validateOrder(form).items);
    assert.throws(() => createOrderPayload(form), OrderValidationError);
  }
});

test('checks text limits after trimming and normalizes only a valid two-letter state', () => {
  const form = formFixture();
  form.customerName = ` ${'a'.repeat(100)} `;
  form.items[0].productName = ` ${'p'.repeat(100)} `;
  form.deliveryAddress.street = ` ${'r'.repeat(150)} `;
  form.deliveryAddress.city = ` ${'c'.repeat(150)} `;
  form.deliveryAddress.number = ` ${'n'.repeat(30)} `;
  assert.deepEqual(validateOrder(form), {});

  const limits = [
    ['customerName', 101], ['street', 151], ['city', 151], ['number', 31],
  ];
  for (const [field, length] of limits) {
    const invalid = formFixture();
    const target = field === 'customerName' ? invalid : invalid.deliveryAddress;
    target[field] = 'a'.repeat(length);
    assert.ok(validateOrder(invalid)[field === 'customerName' ? field : `deliveryAddress.${field}`]);
  }
  const invalidProduct = formFixture();
  invalidProduct.items[0].productName = 'p'.repeat(101);
  assert.ok(validateOrder(invalidProduct)[itemFieldName('item-1', 'productName')]);
  for (const state of ['', 'S', 'SPA', '1P', 'S-P']) {
    form.deliveryAddress.state = state;
    assert.ok(validateOrder(form)['deliveryAddress.state']);
  }
});

test('formats a valid optional zip code without concealing letters, surplus digits or misplaced separators', () => {
  assert.equal(formatZipCodeInput('00000000'), '00000-000');
  assert.equal(formatZipCodeInput('00000-000'), '00000-000');
  assert.equal(formatZipCodeInput(''), '');
  assert.equal(formatZipCodeInput('abcdefgh'), 'abcdefgh');
  assert.equal(formatZipCodeInput('000000000'), '000000000');

  for (const zipCode of [undefined, null, '', '   ']) {
    const form = formFixture();
    form.deliveryAddress.zipCode = zipCode;
    assert.equal(createOrderPayload(form).deliveryAddress.zipCode, null);
  }
  for (const zipCode of ['00000000', '00000-000', ' 00000-000 ']) {
    const form = formFixture();
    form.deliveryAddress.zipCode = zipCode;
    assert.equal(createOrderPayload(form).deliveryAddress.zipCode, '00000000');
  }
  for (const zipCode of ['0000000', '000000000', '0000-0000', 'abcdefgh', '00000-00a']) {
    const form = formFixture();
    form.deliveryAddress.zipCode = zipCode;
    assert.ok(validateOrder(form)['deliveryAddress.zipCode']);
  }
});

test('keeps validation attached to a stable row identity after a middle row is removed', () => {
  const form = formFixture();
  form.items.push({ ...createOrderItem('item-3'), productName: 'Produto C', quantity: '1.5' });
  const errorKey = itemFieldName('item-3', 'quantity');
  assert.ok(validateOrder(form)[errorKey]);

  form.items = form.items.filter(item => item.rowId !== 'item-2');

  assert.deepEqual(form.items.map(item => item.rowId), ['item-1', 'item-3']);
  assert.ok(validateOrder(form)[errorKey]);
  form.items[1].quantity = '3';
  assert.deepEqual(createOrderPayload(form).items, [
    { productName: 'Produto A', quantity: 1 },
    { productName: 'Produto C', quantity: 3 },
  ]);
});
