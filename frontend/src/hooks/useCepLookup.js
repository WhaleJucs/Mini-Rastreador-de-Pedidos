import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from './useAuth.js';
import { lookupCep } from '../services/cepApi.js';
import { createCepLookup } from '../services/cepLookup.js';

const INITIAL_STATE = Object.freeze({ isLoading: false, error: null, message: null });

export function useCepLookup({ address, onApply }) {
  const { session } = useAuth();
  const addressRef = useRef(address);
  addressRef.current = address;
  const lookupRef = useRef(null);
  const [snapshot, setSnapshot] = useState(null);

  useEffect(() => {
    if (!session) return undefined;
    const lookup = createCepLookup({
      load: lookupCep,
      getAddress: () => addressRef.current,
      onApply,
      onChange: state => setSnapshot({ session, state }),
    });
    lookupRef.current = lookup;
    return () => {
      lookup.dispose();
      if (lookupRef.current === lookup) lookupRef.current = null;
    };
  }, [session, onApply]);

  const lookup = useCallback(() => lookupRef.current?.lookup(), []);
  const edit = useCallback(field => lookupRef.current?.edit(field), []);
  const cancel = useCallback(() => lookupRef.current?.cancel(), []);
  const state = snapshot?.session === session ? snapshot.state : INITIAL_STATE;
  return { ...state, lookup, edit, cancel };
}
