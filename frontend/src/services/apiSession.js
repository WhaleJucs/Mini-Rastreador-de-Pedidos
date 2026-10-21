import { CanceledError } from 'axios';
import { isUsableSession } from './authSession.js';

const SESSION_GENERATION = 'foodySessionGeneration';
const ABORT_CONTROLLER = 'foodyAbortController';
const DETACH_CALLER_SIGNAL = 'foodyDetachCallerSignal';

function release(config, pending) {
  const controller = config?.[ABORT_CONTROLLER];
  if (controller) pending.delete(controller);
  config?.[DETACH_CALLER_SIGNAL]?.();
}

function canceled(config, message = 'Operação de sessão encerrada.') {
  return new CanceledError(message, config);
}

export function installApiSessionInterceptors(client, bridge) {
  const pending = new Set();

  const requestInterceptor = client.interceptors.request.use(config => {
    const generation = bridge.getGeneration();
    config[SESSION_GENERATION] = generation;
    if (config.skipAuth === true) return config;

    const session = bridge.getSession();
    if (session && !isUsableSession(session, bridge.now())) {
      bridge.endSession('expired', generation);
      throw canceled(config, 'Sessão expirada.');
    }

    const controller = new AbortController();
    const callerSignal = config.signal;
    if (callerSignal) {
      const abortFromCaller = () => controller.abort(callerSignal.reason);
      if (callerSignal.aborted) abortFromCaller();
      else callerSignal.addEventListener('abort', abortFromCaller, { once: true });
      config[DETACH_CALLER_SIGNAL] = () => callerSignal.removeEventListener('abort', abortFromCaller);
    }
    config.signal = controller.signal;
    config[ABORT_CONTROLLER] = controller;
    pending.add(controller);

    if (session) {
      config.headers.set('Authorization', `${session.tokenType} ${session.accessToken}`);
    }
    return config;
  });

  const responseInterceptor = client.interceptors.response.use(response => {
    release(response.config, pending);
    if (response.config.skipAuth !== true
        && response.config[SESSION_GENERATION] !== bridge.getGeneration()) {
      return Promise.reject(canceled(response.config));
    }
    return response;
  }, error => {
    const config = error.config;
    release(config, pending);
    if (config?.skipAuth !== true
        && config?.[SESSION_GENERATION] !== undefined
        && config[SESSION_GENERATION] !== bridge.getGeneration()) {
      return Promise.reject(canceled(config));
    }
    if (config?.skipAuth !== true && error.response?.status === 401) {
      bridge.endSession('unauthorized', config?.[SESSION_GENERATION]);
    }
    return Promise.reject(error);
  });

  const cancelPending = () => {
    pending.forEach(controller => controller.abort('Sessão encerrada.'));
    pending.clear();
  };

  const disconnect = () => {
    client.interceptors.request.eject(requestInterceptor);
    client.interceptors.response.eject(responseInterceptor);
    cancelPending();
  };

  return Object.freeze({ cancelPending, disconnect });
}
