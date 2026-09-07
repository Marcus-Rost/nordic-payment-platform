import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import nb from './locales/nb-NO.json';
import en from './locales/en-US.json';

const STORAGE_KEY = 'jrpay.client.lang';

const initialLang =
    (typeof localStorage !== 'undefined' && localStorage.getItem(STORAGE_KEY)) ||
    (navigator.language?.toLowerCase().startsWith('en') ? 'en-US' : 'nb-NO');

void i18n.use(initReactI18next).init({
    resources: {
        'nb-NO': { translation: nb },
        'en-US': { translation: en },
    },
    lng: initialLang,
    fallbackLng: 'nb-NO',
    interpolation: { escapeValue: false },
    returnNull: false,
});

export function setLanguage(lang: 'nb-NO' | 'en-US'): void {
    void i18n.changeLanguage(lang);
    localStorage.setItem(STORAGE_KEY, lang);
}

export default i18n;
