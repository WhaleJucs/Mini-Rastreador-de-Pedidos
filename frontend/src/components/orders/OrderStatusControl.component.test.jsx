import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import OrderStatusControl from './OrderStatusControl.jsx';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const order = { id: 3, status: 'RECEBIDO', version: 0 };

describe('OrderStatusControl', () => {
  it('shows only the permitted actions for the current status', () => {
    render(<OrderStatusControl order={order} onSubmit={vi.fn()} onReload={vi.fn()} />);

    expect(screen.getByRole('button', { name: 'Em Preparo' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Cancelar pedido' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Entregue' })).not.toBeInTheDocument();
  });

  it('submits the selected status only after confirmation', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);
    render(<OrderStatusControl order={order} onSubmit={onSubmit} onReload={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: 'Em Preparo' }));
    expect(onSubmit).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Em Preparo' }));

    expect(confirm).toHaveBeenCalledTimes(2);
    expect(onSubmit).toHaveBeenCalledWith(order, 'EM_PREPARO');
  });

  it('blocks new actions after an error and offers an explicit reload', async () => {
    const user = userEvent.setup();
    const onReload = vi.fn();
    render(<OrderStatusControl order={order} state={{ error: 'Falha controlada.' }}
      onSubmit={vi.fn()} onReload={onReload} />);

    expect(screen.getByRole('button', { name: 'Em Preparo' })).toBeDisabled();
    expect(screen.getByRole('alert')).toHaveTextContent('Falha controlada.');
    await user.click(screen.getByRole('button', { name: 'Recarregar dados do pedido' }));
    expect(onReload).toHaveBeenCalledWith(3);
  });

  it('renders no actions for terminal states', () => {
    render(<OrderStatusControl order={{ ...order, status: 'ENTREGUE' }} onSubmit={vi.fn()} onReload={vi.fn()} />);
    expect(screen.queryByRole('group')).not.toBeInTheDocument();
  });
});
