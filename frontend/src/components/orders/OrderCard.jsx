import { orderStatusLabel } from '../../utils/orderStatusLabel.js';

const dateFormatter = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' });

export default function OrderCard({ order, children }) {
  const { deliveryAddress: address } = order;
  return (
    <article className="order-card" aria-labelledby={`order-title-${order.id}`}>
      <header className="order-card-heading">
        <div>
          <p className="eyebrow">Pedido #{order.id}</p>
          <h2 id={`order-title-${order.id}`}>{order.customerName}</h2>
        </div>
        <p className="order-status"><span>Status: </span>{orderStatusLabel(order.status)}</p>
      </header>

      <p className="order-created-at">
        Criado em <time dateTime={order.createdAt}>{dateFormatter.format(new Date(order.createdAt))}</time>
      </p>

      <div className="order-card-details">
        <section aria-labelledby={`order-items-${order.id}`}>
          <h3 id={`order-items-${order.id}`}>Itens</h3>
          <ul className="order-items">
            {order.items.map(item => (
              <li key={item.id}>
                <span>{item.productName}</span>
                <span className="order-item-quantity">Quantidade: {item.quantity}</span>
              </li>
            ))}
          </ul>
        </section>

        <section aria-labelledby={`order-address-${order.id}`}>
          <h3 id={`order-address-${order.id}`}>Endereço de entrega</h3>
          <address className="order-address">
            <span>{address.street}, {address.number}</span>
            <span>{address.city} — {address.state}</span>
            {address.zipCode && <span>CEP {address.zipCode.slice(0, 5)}-{address.zipCode.slice(5)}</span>}
          </address>
        </section>
      </div>
      {children}
    </article>
  );
}
