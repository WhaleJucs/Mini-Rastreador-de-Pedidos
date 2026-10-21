const MAX_QUANTITY = 2_147_483_647;

export class OrderValidationError extends Error {
  constructor(fieldErrors) {
    super('Revise os campos destacados.');
    this.name = 'OrderValidationError';
    this.fieldErrors = fieldErrors;
  }
}

export function createOrderItem(rowId) {
  return { rowId, productName: '', quantity: '1' };
}

export function itemFieldName(rowId, field) {
  return `items.${rowId}.${field}`;
}

function text(value) {
  return typeof value === 'string' ? value.trim() : '';
}

function quantityFrom(value) {
  if (typeof value !== 'string' || !/^[0-9]+$/.test(value.trim())) return null;
  const quantity = Number(value.trim());
  return Number.isSafeInteger(quantity) && quantity > 0 && quantity <= MAX_QUANTITY ? quantity : null;
}

export function formatZipCodeInput(value) {
  if (!/^[0-9]{0,8}$/.test(value)) return value;
  return value.length > 5 ? `${value.slice(0, 5)}-${value.slice(5)}` : value;
}

export function validateOrder(form) {
  const errors = {};
  const validateText = (field, value, maxLength, requiredMessage) => {
    const normalized = text(value);
    if (!normalized) errors[field] = requiredMessage;
    else if (normalized.length > maxLength) errors[field] = `Use no máximo ${maxLength} caracteres.`;
  };

  validateText('customerName', form.customerName, 100, 'Informe o nome do cliente.');

  if (!Array.isArray(form.items) || form.items.length === 0) {
    errors.items = 'Adicione pelo menos um item.';
  } else {
    for (const item of form.items) {
      validateText(itemFieldName(item.rowId, 'productName'), item.productName, 100, 'Informe o nome do produto.');
      if (quantityFrom(item.quantity) === null) {
        errors[itemFieldName(item.rowId, 'quantity')] = 'Informe uma quantidade inteira entre 1 e 2147483647.';
      }
    }
  }

  const address = form.deliveryAddress ?? {};
  validateText('deliveryAddress.street', address.street, 150, 'Informe a rua.');
  validateText('deliveryAddress.number', address.number, 30, 'Informe o número ou S/N.');
  validateText('deliveryAddress.city', address.city, 150, 'Informe a cidade.');
  if (!/^[A-Z]{2}$/.test(text(address.state).toUpperCase())) {
    errors['deliveryAddress.state'] = 'Informe uma UF com duas letras.';
  }
  const zipCode = text(address.zipCode);
  if (zipCode && !/^[0-9]{5}-?[0-9]{3}$/.test(zipCode)) {
    errors['deliveryAddress.zipCode'] = 'Informe um CEP com oito dígitos.';
  }

  return errors;
}

export function createOrderPayload(form) {
  const errors = validateOrder(form);
  if (Object.keys(errors).length > 0) throw new OrderValidationError(errors);
  const address = form.deliveryAddress;
  const zipCode = text(address.zipCode);
  return {
    customerName: text(form.customerName),
    deliveryAddress: {
      street: text(address.street),
      number: text(address.number),
      city: text(address.city),
      state: text(address.state).toUpperCase(),
      zipCode: zipCode ? zipCode.replace('-', '') : null,
    },
    items: form.items.map(item => ({ productName: text(item.productName), quantity: quantityFrom(item.quantity) })),
  };
}
