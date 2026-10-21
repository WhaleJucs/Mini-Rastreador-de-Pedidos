import { normalizeZipCode } from '../utils/zipCode.js';

export class CepLookupError extends Error {}

export async function lookupCep(zipCode, { signal, fetcher = globalThis.fetch } = {}) {
  const normalized = normalizeZipCode(zipCode);
  if (!normalized) throw new CepLookupError('Informe um CEP com oito dígitos para buscar o endereço.');
  const response = await fetcher(`https://viacep.com.br/ws/${normalized}/json/`, {
    method: 'GET', signal, credentials: 'omit', referrerPolicy: 'no-referrer',
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) throw new CepLookupError('Não foi possível consultar o CEP. Preencha o endereço manualmente ou tente novamente.');
  let data;
  try {
    data = await response.json();
  } catch {
    throw new CepLookupError('O serviço de CEP retornou uma resposta inválida. Você pode preencher o endereço manualmente.');
  }
  if (data?.erro === true || data?.erro === 'true') {
    throw new CepLookupError('CEP não encontrado. Confira o CEP ou preencha o endereço manualmente.');
  }
  if (normalizeZipCode(data?.cep) !== normalized
      || typeof data?.logradouro !== 'string' || data.logradouro.trim().length > 150
      || typeof data?.localidade !== 'string' || data.localidade.trim().length > 150
      || typeof data?.uf !== 'string' || !/^(?:[A-Z]{2})?$/.test(data.uf.trim().toUpperCase())) {
    throw new CepLookupError('O serviço de CEP retornou uma resposta inválida. Você pode preencher o endereço manualmente.');
  }
  return { zipCode: normalized, street: data.logradouro.trim(), city: data.localidade.trim(), state: data.uf.trim().toUpperCase() };
}
