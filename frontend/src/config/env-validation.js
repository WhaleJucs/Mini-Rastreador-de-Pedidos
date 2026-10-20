export function resolveEnv(values) {
  const expectedEnvironment = { development: 'local', hml: 'hml', production: 'prod' }[values.MODE];
  if (!expectedEnvironment || values.VITE_APP_ENV !== expectedEnvironment) {
    throw new Error('VITE_APP_ENV deve corresponder ao modo development/local, hml/hml ou production/prod.');
  }

  const apiBaseUrl = values.VITE_API_BASE_URL?.trim().replace(/\/+$/, '');
  const fail = () => { throw new Error('VITE_API_BASE_URL exige uma base /api pública válida para o ambiente.'); };
  if (!apiBaseUrl) fail();
  if (apiBaseUrl !== '/api' || expectedEnvironment !== 'local') {
    let url;
    try { url = new URL(apiBaseUrl); } catch { fail(); }
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
        || url.search || url.hash || url.pathname !== '/api') fail();
    if (expectedEnvironment !== 'local' && (url.protocol !== 'https:'
        || url.hostname === 'localhost' || url.hostname.endsWith('.localhost')
        || url.hostname.startsWith('127.') || ['[::1]', '0.0.0.0'].includes(url.hostname))) fail();
  }
  return Object.freeze({ appEnv: expectedEnvironment, apiBaseUrl });
}


