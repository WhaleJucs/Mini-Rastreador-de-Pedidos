import { itemFieldName } from '../../utils/orderValidation.js';

export default function OrderItemsFields({ items, errors, onChange, onAdd, onRemove }) {
  return (
    <fieldset className="order-form-section" name="items" tabIndex={-1}>
      <legend>Itens do pedido</legend>
      <p className="order-section-description">Informe os produtos e suas quantidades.</p>
      {errors.items && <p className="field-error" role="alert">{errors.items}</p>}

      <div className="order-items-fields">
        {items.map((item, index) => {
          const productField = itemFieldName(item.rowId, 'productName');
          const quantityField = itemFieldName(item.rowId, 'quantity');
          const productId = `order-${item.rowId}-product`;
          const quantityId = `order-${item.rowId}-quantity`;
          return (
            <fieldset className="order-item-fields" key={item.rowId}>
              <legend>Item {index + 1}</legend>
              <div className="order-item-grid">
                <div className="form-field">
                  <label htmlFor={productId}>Nome do produto</label>
                  <input
                    id={productId}
                    name={productField}
                    type="text"
                    value={item.productName}
                    onChange={event => onChange(item.rowId, 'productName', event.target.value)}
                    required
                    aria-invalid={Boolean(errors[productField])}
                    aria-describedby={errors[productField] ? `${productId}-error` : undefined}
                  />
                  {errors[productField] && <p id={`${productId}-error`} className="field-error">{errors[productField]}</p>}
                </div>

                <div className="form-field">
                  <label htmlFor={quantityId}>Quantidade</label>
                  <input
                    id={quantityId}
                    name={quantityField}
                    type="number"
                    inputMode="numeric"
                    min="1"
                    max="2147483647"
                    step="1"
                    value={item.quantity}
                    onChange={event => onChange(item.rowId, 'quantity', event.target.value)}
                    required
                    aria-invalid={Boolean(errors[quantityField])}
                    aria-describedby={errors[quantityField] ? `${quantityId}-error` : undefined}
                  />
                  {errors[quantityField] && <p id={`${quantityId}-error`} className="field-error">{errors[quantityField]}</p>}
                </div>

                <button
                  className="secondary-button order-remove-item"
                  type="button"
                  onClick={() => onRemove(item.rowId)}
                  disabled={items.length === 1}
                  aria-label={`Remover item ${index + 1}`}
                >
                  Remover
                </button>
              </div>
            </fieldset>
          );
        })}
      </div>

      <div className="order-items-actions">
        <button className="secondary-button" type="button" onClick={onAdd}>Adicionar item</button>
        <p className="field-hint">Mantenha pelo menos um item no pedido.</p>
      </div>
    </fieldset>
  );
}
