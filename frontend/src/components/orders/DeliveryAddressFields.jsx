import { formatZipCodeInput } from '../../utils/orderValidation.js';

const FIELDS = [
  { field: 'street', label: 'Rua', autoComplete: 'address-line1', wide: true },
  { field: 'number', label: 'Número', autoComplete: 'off', hint: 'Pode informar S/N.' },
  { field: 'city', label: 'Cidade', autoComplete: 'address-level2' },
  { field: 'state', label: 'UF', autoComplete: 'address-level1' },
  { field: 'zipCode', label: 'CEP (opcional)', autoComplete: 'postal-code', hint: 'Formato: 00000-000.', optional: true },
];

export default function DeliveryAddressFields({ address, errors, onChange, cepLookup }) {
  return (
    <fieldset className="order-form-section">
      <legend>Endereço de entrega</legend>
      <p className="order-section-description">Preencha rua, número, cidade e UF. O CEP é opcional.</p>
      <div className="order-address-fields">
        {FIELDS.map(({ field, label, autoComplete, wide, hint, optional }) => {
          const name = `deliveryAddress.${field}`;
          const id = `order-address-${field}`;
          const describedBy = [errors[name] && `${id}-error`, hint && `${id}-hint`].filter(Boolean).join(' ');
          return (
            <div className={`form-field${wide ? ' order-field-wide' : ''}`} key={field}>
              <label htmlFor={id}>{label}</label>
              <input
                id={id}
                name={name}
                type="text"
                autoComplete={autoComplete}
                inputMode={field === 'zipCode' ? 'numeric' : undefined}
                autoCapitalize={field === 'state' ? 'characters' : undefined}
                value={address[field]}
                onChange={event => onChange(field,
                  field === 'zipCode' ? formatZipCodeInput(event.target.value) : event.target.value)}
                required={!optional}
                aria-invalid={Boolean(errors[name])}
                aria-describedby={describedBy || undefined}
              />
              {errors[name] && <p id={`${id}-error`} className="field-error">{errors[name]}</p>}
              {hint && <p id={`${id}-hint`} className="field-hint">{hint}</p>}
            </div>
          );
        })}
      </div>
      <div className="order-items-actions">
        <button className="secondary-button" type="button" onClick={cepLookup.lookup} disabled={cepLookup.isLoading}>
          {cepLookup.isLoading ? 'Buscando endereço…' : 'Buscar endereço por CEP'}
        </button>
        <p className="field-hint">A busca é opcional. Você pode preencher e corrigir o endereço manualmente.</p>
      </div>
      <div aria-live="polite" aria-busy={cepLookup.isLoading}>
        {cepLookup.isLoading && <p className="field-hint" role="status">Consultando CEP…</p>}
        {cepLookup.message && <p className="field-hint" role="status">{cepLookup.message}</p>}
        {cepLookup.error && <p className="field-error" role="alert">{cepLookup.error}</p>}
      </div>
    </fieldset>
  );
}
