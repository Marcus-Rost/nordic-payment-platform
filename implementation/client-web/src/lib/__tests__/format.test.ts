import { describe, expect, it } from 'vitest';
import { formatAmount, formatDate, formatDateTime, isPast, maskIban } from '../format';

describe('format helpers', () => {
    it('formats currency with ISO code, not symbol', () => {
        const out = formatAmount('1234.56', 'NOK', 'nb-NO');
        expect(out).toContain('NOK');
        expect(out).toMatch(/1.?234[,.]56/);
    });

    it('returns "<amount> <currency>" if amount is non-numeric', () => {
        expect(formatAmount('NaN', 'NOK', 'en-US')).toBe('NaN NOK');
    });

    it('formats date and date-time without throwing', () => {
        expect(formatDateTime('2026-04-26T10:00:00Z', 'nb-NO')).toBeTruthy();
        expect(formatDate('2026-04-26', 'nb-NO')).toBeTruthy();
    });

    it('isPast returns true for past iso', () => {
        expect(isPast('2000-01-01T00:00:00Z')).toBe(true);
        expect(isPast('2999-01-01T00:00:00Z')).toBe(false);
    });

    it('maskIban keeps first 4 + last 4', () => {
        expect(maskIban('NO9300000000123456')).toBe('NO93 •••• •••• 3456');
    });

    it('maskIban returns short input untouched', () => {
        expect(maskIban('NO93')).toBe('NO93');
    });
});
