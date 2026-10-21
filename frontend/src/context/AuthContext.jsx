import { createContext, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { CanceledError } from 'axios';
import { env } from '../config/env.js';
import { connectApiSession } from '../services/api.js';
import { login as requestLogin, register as requestRegistration } from '../services/authApi.js';
import { sessionFromLogin, restoreSession } from '../services/authSession.js';
import { createAuthStorage } from '../services/authStorage.js';

export const AuthContext = createContext(null);

const storage = createAuthStorage(env.appEnv);

export function AuthProvider({ children }) {
  const [session, setSession] = useState(() => restoreSession(storage));
  const [sessionEndReason, setSessionEndReason] = useState(null);
  const [apiReady, setApiReady] = useState(false);
  const sessionRef = useRef(session);
  const generationRef = useRef(0);
  const loginAttemptRef = useRef(0);
  const loginControllerRef = useRef(null);
  const cancelPendingRef = useRef(() => {});

  const endSession = useCallback((reason = 'logout', expectedGeneration) => {
    if (expectedGeneration !== undefined && expectedGeneration !== generationRef.current) return false;
    loginControllerRef.current?.abort('Sessão encerrada.');
    loginControllerRef.current = null;
    cancelPendingRef.current();
    generationRef.current += 1;
    loginAttemptRef.current += 1;
    sessionRef.current = null;
    storage.clear();
    setSession(null);
    setSessionEndReason(reason);
    return reason;
  }, []);

  const login = useCallback(async (credentials, { signal } = {}) => {
    loginControllerRef.current?.abort('Tentativa de login substituída.');
    const controller = new AbortController();
    loginControllerRef.current = controller;
    const attempt = ++loginAttemptRef.current;
    const generation = generationRef.current;
    const abortFromCaller = () => controller.abort(signal?.reason);
    if (signal?.aborted) abortFromCaller();
    else signal?.addEventListener('abort', abortFromCaller, { once: true });
    let response;
    try {
      response = await requestLogin(credentials, { signal: controller.signal });
    } finally {
      signal?.removeEventListener('abort', abortFromCaller);
      if (loginControllerRef.current === controller) loginControllerRef.current = null;
    }
    if (attempt !== loginAttemptRef.current || generation !== generationRef.current) {
      throw new CanceledError('Tentativa de login substituída.');
    }
    const nextSession = sessionFromLogin(response);
    cancelPendingRef.current();
    generationRef.current += 1;
    sessionRef.current = nextSession;
    storage.write(nextSession);
    setSession(nextSession);
    setSessionEndReason(null);
    return nextSession;
  }, []);

  const logout = useCallback(() => endSession('logout'), [endSession]);

  useEffect(() => {
    const connection = connectApiSession({
      getSession: () => sessionRef.current,
      getGeneration: () => generationRef.current,
      endSession,
      now: Date.now,
    });
    cancelPendingRef.current = connection.cancelPending;
    setApiReady(true);
    return () => {
      loginControllerRef.current?.abort('Provider desmontado.');
      loginControllerRef.current = null;
      cancelPendingRef.current = () => {};
      connection.disconnect();
    };
  }, [endSession]);

  useEffect(() => {
    if (!session) return undefined;
    const generation = generationRef.current;
    let timer;
    const expire = () => {
      const remaining = session.expiresAt - Date.now();
      if (remaining <= 0) endSession('expired', generation);
      else timer = globalThis.setTimeout(expire, Math.min(remaining, 2_147_483_647));
    };
    expire();
    return () => globalThis.clearTimeout(timer);
  }, [endSession, session]);

  const value = useMemo(() => ({
    session,
    user: session?.user ?? null,
    isAuthenticated: session !== null,
    sessionEndReason,
    login,
    logout,
    register: requestRegistration,
  }), [login, logout, session, sessionEndReason]);

  return <AuthContext value={value}>{apiReady ? children : null}</AuthContext>;
}
