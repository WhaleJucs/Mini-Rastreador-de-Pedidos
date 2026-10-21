import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from './useAuth.js';
import { listOrders } from '../services/ordersApi.js';
import { createOrdersReader } from '../services/ordersReader.js';

const INITIAL_STATE = Object.freeze({ orders: [], hasLoaded: false, isLoading: true, error: null });

export function useOrders() {
  const { session } = useAuth();
  const [snapshot, setSnapshot] = useState(null);
  const readerRef = useRef(null);

  useEffect(() => {
    if (!session) return undefined;
    const reader = createOrdersReader({
      load: listOrders,
      onChange: state => setSnapshot({ session, state }),
    });
    readerRef.current = reader;
    reader.refresh();
    return () => {
      reader.dispose();
      if (readerRef.current === reader) readerRef.current = null;
    };
  }, [session]);

  const refresh = useCallback(() => readerRef.current?.refresh(), []);
  // Antes de uma mutação futura aplicar dados confirmados, invalida a leitura anterior.
  const invalidate = useCallback(() => readerRef.current?.invalidate(), []);
  const state = snapshot?.session === session ? snapshot.state : INITIAL_STATE;

  return { ...state, refresh, invalidate };
}
