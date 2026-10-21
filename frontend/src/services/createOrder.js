import { itemFieldName } from '../utils/orderValidation.js';

const FIELD_MESSAGES = {
  customerName: 'Confira o nome do cliente.',
  items: 'Confira os itens do pedido.',
  'deliveryAddress.street': 'Confira a rua.',
  'deliveryAddress.number': 'Confira o número ou informe S/N.',
  'deliveryAddress.city': 'Confira a cidade.',
  'deliveryAddress.state': 'Informe uma UF com duas letras.',
  'deliveryAddress.zipCode': 'Informe um CEP com oito dígitos ou deixe em branco.',
};

export class CreateOrderError extends Error {
  constructor(message, { fieldErrors = {}, uncertain = false } = {}) {
    super(message);
    this.name = 'CreateOrderError';
    this.fieldErrors = fieldErrors;
    this.uncertain = uncertain;
  }
}

export function createOrderErrorFrom(error) {
  const status = error?.response?.status;
  const problem = error?.response?.data;
  if (status === 400) {
    const fieldErrors = {};
    if (problem?.code === 'VALIDATION_ERROR' && Array.isArray(problem.violations)) {
      for (const violation of problem.violations) {
        const field = violation?.field;
        if (typeof field !== 'string') continue;
        if (Object.hasOwn(FIELD_MESSAGES, field)) fieldErrors[field] = FIELD_MESSAGES[field];
        else {
          const itemField = /^items\[([0-9]+)\]\.(productName|quantity)$/.exec(field);
          if (itemField) {
            fieldErrors[field] = itemField[2] === 'productName'
              ? 'Confira o nome do produto.'
              : 'Informe uma quantidade inteira entre 1 e 2147483647.';
          }
        }
      }
    }
    return new CreateOrderError('Revise os dados do pedido e tente novamente.', { fieldErrors });
  }
  if (status === 401) return new CreateOrderError('Sua sessão expirou. Entre novamente.');
  if (status === 503 && problem?.code === 'DATABASE_BUSY') {
    return new CreateOrderError('O banco de dados está temporariamente ocupado. Tente novamente em instantes.');
  }
  return new CreateOrderError(
    'Não foi possível confirmar se o pedido foi criado. Confira a lista de pedidos antes de tentar novamente.',
    { uncertain: true },
  );
}

export function orderFieldErrorsForForm(fieldErrors, items) {
  const mapped = {};
  for (const [field, message] of Object.entries(fieldErrors)) {
    if (Object.hasOwn(FIELD_MESSAGES, field)) mapped[field] = message;
    else {
      const itemField = /^items\[([0-9]+)\]\.(productName|quantity)$/.exec(field);
      const index = itemField ? Number(itemField[1]) : -1;
      if (Number.isSafeInteger(index) && items[index]) {
        mapped[itemFieldName(items[index].rowId, itemField[2])] = message;
      }
    }
  }
  return mapped;
}

export function createOrderSender({ send, onChange, onSuccess }) {
  let inFlight = false;
  let completed = false;
  let uncertain = false;
  let disposed = false;
  let generation = 0;
  let controller = null;

  async function submit(payload) {
    if (disposed || inFlight || completed || uncertain) return null;
    inFlight = true;
    const currentGeneration = ++generation;
    const currentController = new AbortController();
    controller = currentController;
    const isCurrent = () => !disposed
      && generation === currentGeneration
      && !currentController.signal.aborted;
    onChange({ isSubmitting: true, error: null });
    let order;
    try {
      order = await send(payload, { signal: currentController.signal });
    } catch (error) {
      if (!isCurrent()) return null;
      if (error?.code === 'ERR_CANCELED') {
        onChange({ isSubmitting: false, error: null });
        return null;
      }
      const failure = createOrderErrorFrom(error);
      uncertain = failure.uncertain;
      onChange({ isSubmitting: false, error: failure });
      return { error: failure };
    } finally {
      if (isCurrent()) {
        inFlight = false;
        controller = null;
      }
    }
    if (!isCurrent()) return null;
    completed = true;
    onChange({ isSubmitting: false, error: null });
    onSuccess(order);
    return { order };
  }

  function clearError() {
    if (!disposed && !inFlight && !completed && !uncertain) onChange({ isSubmitting: false, error: null });
  }

  function dispose() {
    disposed = true;
    generation += 1;
    controller?.abort();
    controller = null;
  }

  return Object.freeze({ submit, clearError, dispose });
}
