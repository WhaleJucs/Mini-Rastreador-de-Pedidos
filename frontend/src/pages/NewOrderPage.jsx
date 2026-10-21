import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import DeliveryAddressFields from '../components/orders/DeliveryAddressFields.jsx';
import OrderItemsFields from '../components/orders/OrderItemsFields.jsx';
import { useAuth } from '../hooks/useAuth.js';
import { createOrderItem, itemFieldName, validateOrder } from '../utils/orderValidation.js';

export default function NewOrderPage() {
  const { logout, user } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState(() => ({
    customerName: '',
    deliveryAddress: { street: '', number: '', city: '', state: '', zipCode: '' },
    items: [createOrderItem('item-1')],
  }));
  const [fieldErrors, setFieldErrors] = useState({});
  const [formMessage, setFormMessage] = useState(null);
  const [focusField, setFocusField] = useState(null);
  const formRef = useRef(null);
  const nextItemId = useRef(2);

  useEffect(() => {
    if (!focusField) return;
    formRef.current?.elements.namedItem(focusField)?.focus();
    setFocusField(null);
  }, [focusField]);

  function clearFieldError(name) {
    setFieldErrors(current => {
      if (current[name] === undefined) return current;
      const next = { ...current };
      delete next[name];
      return next;
    });
    setFormMessage(null);
  }

  function updateCustomerName(event) {
    const value = event.target.value;
    setForm(current => ({ ...current, customerName: value }));
    clearFieldError('customerName');
  }

  function updateAddress(field, value) {
    setForm(current => ({ ...current, deliveryAddress: { ...current.deliveryAddress, [field]: value } }));
    clearFieldError(`deliveryAddress.${field}`);
  }

  function updateItem(rowId, field, value) {
    setForm(current => ({
      ...current,
      items: current.items.map(item => item.rowId === rowId ? { ...item, [field]: value } : item),
    }));
    clearFieldError(itemFieldName(rowId, field));
  }

  function addItem() {
    const item = createOrderItem(`item-${nextItemId.current++}`);
    setForm(current => ({ ...current, items: [...current.items, item] }));
    clearFieldError('items');
    setFocusField(itemFieldName(item.rowId, 'productName'));
  }

  function removeItem(rowId) {
    if (form.items.length <= 1) return;
    const index = form.items.findIndex(item => item.rowId === rowId);
    if (index < 0) return;
    const remaining = form.items.filter(item => item.rowId !== rowId);
    setForm(current => ({ ...current, items: current.items.filter(item => item.rowId !== rowId) }));
    setFieldErrors(current => {
      const next = { ...current };
      delete next[itemFieldName(rowId, 'productName')];
      delete next[itemFieldName(rowId, 'quantity')];
      return next;
    });
    setFormMessage(null);
    setFocusField(itemFieldName(remaining[Math.min(index, remaining.length - 1)].rowId, 'productName'));
  }

  function handleValidate(event) {
    event.preventDefault();
    const errors = validateOrder(form);
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) {
      setFormMessage({ type: 'error', text: 'Revise os campos destacados.' });
      const firstField = Object.keys(errors).find(name => formRef.current?.elements.namedItem(name));
      formRef.current?.elements.namedItem(firstField)?.focus();
      return;
    }
    setFormMessage({ type: 'info', text: 'Dados conferidos. O pedido ainda não foi enviado.' });
  }

  function handleLogout() {
    logout();
    navigate('/login', { replace: true });
  }

  return (
    <main className="protected-page">
      <header className="app-header">
        <Link className="wordmark wordmark-static" to="/orders">
          <span aria-hidden="true">F</span>
          Foody Delivery
        </Link>
        <div className="app-session">
          <span>{user.name}</span>
          <button className="secondary-button" type="button" onClick={handleLogout}>Sair</button>
        </div>
      </header>

      <section className="new-order-content" aria-labelledby="new-order-title">
        <header className="new-order-heading">
          <Link className="text-link" to="/orders">Voltar aos pedidos</Link>
          <p className="eyebrow">Uma nova entrega</p>
          <h1 id="new-order-title">Novo pedido</h1>
          <p>Preencha os dados do cliente, os itens e o endereço de entrega.</p>
        </header>

        <form ref={formRef} className="order-form" onSubmit={handleValidate} noValidate>
          <fieldset className="order-form-section">
            <legend>Cliente</legend>
            <p className="order-section-description">Quem receberá o pedido?</p>
            <div className="form-field">
              <label htmlFor="order-customer-name">Nome do cliente</label>
              <input
                id="order-customer-name"
                name="customerName"
                type="text"
                autoComplete="name"
                value={form.customerName}
                onChange={updateCustomerName}
                required
                aria-invalid={Boolean(fieldErrors.customerName)}
                aria-describedby={fieldErrors.customerName ? 'order-customer-name-error' : undefined}
              />
              {fieldErrors.customerName && (
                <p id="order-customer-name-error" className="field-error">{fieldErrors.customerName}</p>
              )}
            </div>
          </fieldset>

          <OrderItemsFields
            items={form.items}
            errors={fieldErrors}
            onChange={updateItem}
            onAdd={addItem}
            onRemove={removeItem}
          />
          <DeliveryAddressFields address={form.deliveryAddress} errors={fieldErrors} onChange={updateAddress} />

          {formMessage && (
            <div
              className={`form-message form-message-${formMessage.type} order-form-message`}
              role={formMessage.type === 'error' ? 'alert' : 'status'}
            >
              {formMessage.text}
            </div>
          )}

          <div className="order-form-actions">
            <Link className="text-link" to="/orders">Cancelar</Link>
            <button className="primary-button" type="submit">Conferir dados</button>
          </div>
        </form>
      </section>
    </main>
  );
}
