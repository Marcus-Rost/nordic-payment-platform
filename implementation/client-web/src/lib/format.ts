// Locale-aware formatters used across customer pages.
//
// Currency display uses the ISO code (NOK / EUR / SEK / DKK / USD) instead of
// the localised symbol so amounts read identically regardless of OS locale —
// this matches the way our Norwegian banking copy displays amounts.

export function formatAmount(amount: string | number, currency: string, locale: string): string {
    const n = typeof amount === 'string' ? Number(amount) : amount;
    if (!Number.isFinite(n)) return `${amount} ${currency}`;
    try {
        return new Intl.NumberFormat(locale, {
            style: 'currency',
            currency,
            currencyDisplay: 'code',
        }).format(n);
    } catch {
        return `${amount} ${currency}`;
    }
}

export function formatDateTime(iso: string, locale: string): string {
    try {
        return new Intl.DateTimeFormat(locale, {
            dateStyle: 'short',
            timeStyle: 'short',
        }).format(new Date(iso));
    } catch {
        return iso;
    }
}

export function formatDate(iso: string, locale: string): string {
    try {
        return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(new Date(iso));
    } catch {
        return iso;
    }
}

export function isPast(iso: string): boolean {
    const t = Date.parse(iso);
    return Number.isFinite(t) && t < Date.now();
}

export function maskIban(iban: string): string {
    if (!iban || iban.length < 8) return iban;
    return iban.slice(0, 4) + ' •••• •••• ' + iban.slice(-4);
}
