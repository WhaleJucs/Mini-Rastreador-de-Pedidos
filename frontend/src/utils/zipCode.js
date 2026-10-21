export function normalizeZipCode(value) {
  if (typeof value !== 'string' || !/^[0-9]{5}-?[0-9]{3}$/.test(value.trim())) return null;
  return value.trim().replace('-', '');
}
