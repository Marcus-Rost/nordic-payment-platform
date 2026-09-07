import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { Modal } from '../Modal';

describe('Modal', () => {
    it('renders with role=dialog + aria-modal', () => {
        render(
            <Modal titleId="t" onClose={() => {}}>
                <h2 id="t">x</h2>
                <button>OK</button>
            </Modal>
        );
        const d = screen.getByRole('dialog');
        expect(d).toHaveAttribute('aria-modal', 'true');
    });

    it('focuses first focusable on open', () => {
        render(
            <Modal titleId="t" onClose={() => {}}>
                <h2 id="t">x</h2>
                <button>First</button>
                <button>Second</button>
            </Modal>
        );
        expect(screen.getByText('First')).toHaveFocus();
    });

    it('closes on ESC', () => {
        const onClose = vi.fn();
        render(
            <Modal titleId="t" onClose={onClose}>
                <h2 id="t">x</h2>
                <button>OK</button>
            </Modal>
        );
        fireEvent.keyDown(document, { key: 'Escape' });
        expect(onClose).toHaveBeenCalled();
    });
});
