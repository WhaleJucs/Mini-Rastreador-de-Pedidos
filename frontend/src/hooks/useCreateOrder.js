import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from './useAuth.js';
import { createOrder } from '../services/ordersApi.js';
import { createOrderSender } from '../services/createOrder.js';

const INITIAL_STATE = Object.freeze({ isSubmitting: false, error: null });

export function useCreateOrder({ onSuccess }) {
  const { session } = useAuth();
  const [snapshot, setSnapshot] = useState(null);
  const senderRef = useRef(null);

  useEffect(() => {
    if (!session) return undefined;
    const sender = createOrderSender({
      send: createOrder,
      onChange: state => setSnapshot({ session, state }),
      onSuccess,
    });
    senderRef.current = sender;
    return () => {
      sender.dispose();
      if (senderRef.current === sender) senderRef.current = null;
    };
  }, [session, onSuccess]);

  const submit = useCallback(payload => senderRef.current?.submit(payload), []);
  const clearError = useCallback(() => senderRef.current?.clearError(), []);
  const state = snapshot?.session === session ? snapshot.state : INITIAL_STATE;
  return { ...state, isUncertain: Boolean(state.error?.uncertain), submit, clearError };
}
