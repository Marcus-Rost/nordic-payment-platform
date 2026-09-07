import React from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './app/App';
import './i18n/i18n';
import './styles/main.css';

const container = document.getElementById('root');
if (!container) {
    throw new Error('Root container #root not found');
}

createRoot(container).render(
    <React.StrictMode>
        <BrowserRouter>
            <App />
        </BrowserRouter>
    </React.StrictMode>
);
