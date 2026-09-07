import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { Modal } from '../Modal';

describe('Modal', () => {
    it('renders with role=dialog and aria-modal', () => {
        render(
            <Modal titleId="t" onClose={() => {}}>
                <h2 id="t">Title</h2>
                <button>OK</button>
            </Modal>
        );
        const dialog = screen.getByRole('dialog');
        expect(dialog).toHaveAttribute('aria-modal', 'true');
        expect(dialog).toHaveAttribute('aria-labelledby', 't');
    });

    it('focuses the first focusable element on open', () => {
        render(
            <Modal titleId="t" onClose={() => {}}>
                <h2 id="t">Title</h2>
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
                <h2 id="t">Title</h2>
                <button>OK</button>
            </Modal>
        );
        fireEvent.keyDown(document, { key: 'Escape' });
        expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('closes when clicking the backdrop', () => {
        const onClose = vi.fn();
        render(
            <Modal titleId="t" onClose={onClose}>
                <h2 id="t">Title</h2>
                <button>OK</button>
            </Modal>
        );
        const backdrop = screen.getByRole('dialog');
        fireEvent.mouseDown(backdrop, { target: backdrop, currentTarget: backdrop });
        expect(onClose).toHaveBeenCalled();
    });
});
