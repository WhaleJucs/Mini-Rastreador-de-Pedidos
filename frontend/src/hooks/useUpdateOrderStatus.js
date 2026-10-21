import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from './useAuth.js';
import { updateOrderStatus } from '../services/ordersApi.js';
import { createOrderStatusUpdater } from '../services/orderStatusUpdate.js';

export function useUpdateOrderStatus({ invalidate, applyConfirmed, refresh }) {
  const { session } = useAuth();
  const updaterRef = useRef(null);
  const [snapshot, setSnapshot] = useState(null);

  useEffect(() => {
    if (!session) return undefined;
    const updater = createOrderStatusUpdater({
      send: updateOrderStatus,
      reload: refresh,
      onStart: invalidate,
      onConfirmed: applyConfirmed,
      onChange: (id, state) => setSnapshot(previous => ({
        session,
        states: { ...(previous?.session === session ? previous.states : {}), [id]: state },
      })),
    });
    updaterRef.current = updater;
    return () => {
      updater.dispose();
      if (updaterRef.current === updater) updaterRef.current = null;
    };
  }, [session, invalidate, applyConfirmed, refresh]);

  const submit = useCallback((order, status) => updaterRef.current?.submit(order, status), []);
  const reload = useCallback(id => updaterRef.current?.refresh(id), []);
  return { states: snapshot?.session === session ? snapshot.states : {}, submit, reload };
}
