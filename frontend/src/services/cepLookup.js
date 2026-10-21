import { normalizeZipCode } from '../utils/zipCode.js';
import { CepLookupError } from './cepApi.js';

const FIELDS = ['zipCode', 'street', 'city', 'state'];
const NETWORK_ERROR = 'Não foi possível consultar o CEP. Preencha o endereço manualmente ou tente novamente.';

export function createCepLookup({ load, getAddress, onApply, onChange, timeoutMs = 8000,
  setTimer = globalThis.setTimeout, clearTimer = globalThis.clearTimeout }) {
  let generation = 0;
  let controller = null;
  let timer = null;
  let disposed = false;
  const edits = { zipCode: 0, street: 0, city: 0, state: 0 };

  function cancel() {
    if (disposed) return;
    generation += 1;
    controller?.abort();
    controller = null;
    clearTimer(timer);
    timer = null;
    onChange({ isLoading: false, error: null, message: null });
  }

  function edit(field) {
    if (Object.hasOwn(edits, field)) edits[field] += 1;
    if (field === 'zipCode') cancel();
  }

  async function lookup() {
    if (disposed || controller) return;
    const zipCode = normalizeZipCode(getAddress().zipCode);
    if (!zipCode) {
      onChange({ isLoading: false, error: 'Informe um CEP com oito dígitos para buscar o endereço.', message: null });
      return;
    }
    const currentGeneration = ++generation;
    const capturedEdits = { ...edits };
    const currentController = new AbortController();
    controller = currentController;
    const isCurrent = () => !disposed && generation === currentGeneration && !currentController.signal.aborted;
    onChange({ isLoading: true, error: null, message: null });
    timer = setTimer(() => {
      if (!isCurrent()) return;
      generation += 1;
      currentController.abort();
      controller = null;
      timer = null;
      onChange({ isLoading: false, error: 'A busca por CEP demorou demais. Preencha o endereço manualmente ou tente novamente.', message: null });
    }, timeoutMs);
    try {
      const address = await load(zipCode, { signal: currentController.signal });
      if (!isCurrent() || normalizeZipCode(getAddress().zipCode) !== zipCode) return;
      const patch = {};
      for (const field of FIELDS) {
        if (edits[field] === capturedEdits[field] && address[field]) patch[field] = address[field];
      }
      onApply(patch);
      onChange({ isLoading: false, error: null, message: 'Consulta concluída. Confira o endereço e mantenha o número preenchido manualmente.' });
    } catch (error) {
      if (!isCurrent()) return;
      onChange({ isLoading: false, error: error instanceof CepLookupError ? error.message : NETWORK_ERROR, message: null });
    } finally {
      if (isCurrent()) {
        clearTimer(timer);
        timer = null;
        controller = null;
      }
    }
  }

  function dispose() {
    if (disposed) return;
    disposed = true;
    generation += 1;
    controller?.abort();
    controller = null;
    clearTimer(timer);
    timer = null;
  }

  return Object.freeze({ lookup, edit, cancel, dispose });
}
