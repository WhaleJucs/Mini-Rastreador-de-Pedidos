import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import OrderCard from './OrderCard.jsx';

afterEach(cleanup);

const order = {
  id: 7,
  customerName: 'Cliente Teste',
  status: 'EM_PREPARO',
  createdAt: '2026-10-21T18:00:00Z',
  deliveryAddress: {
    street: 'Rua Exemplo', number: '42', city: 'São Paulo', state: 'SP', zipCode: '01234567',
  },
  items: [
    { id: 11, productName: 'Pizza', quantity: 2 },
    { id: 12, productName: 'Suco', quantity: 1 },
  ],
};

describe('OrderCard', () => {
  it('renders readable status, items and delivery address', () => {
    render(<OrderCard order={order} />);

    expect(screen.getByRole('heading', { name: 'Cliente Teste' })).toBeInTheDocument();
    expect(screen.getByText('Em Preparo')).toBeInTheDocument();
    expect(screen.getByText('Pizza')).toBeInTheDocument();
    expect(screen.getByText('Quantidade: 2')).toBeInTheDocument();
    expect(screen.getByText('Rua Exemplo, 42')).toBeInTheDocument();
    expect(screen.getByText('CEP 01234-567')).toBeInTheDocument();
  });
});
