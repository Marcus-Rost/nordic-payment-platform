/** @type {import('tailwindcss').Config} */
export default {
    content: ['./index.html', './src/**/*.{ts,tsx}'],
    theme: {
        extend: {
            colors: {
                'jrpay-navy': '#0d2247',
                'jrpay-navy-soft': '#1c3563',
                'jrpay-amber': '#f9a825',
                'jrpay-green': '#2e7d32',
                'jrpay-red': '#c62828',
                // Score-band colours per UC-G31 §12.
                'score-low': '#388e3c',
                'score-medium': '#f9a825',
                'score-high': '#c62828',
            },
            fontFamily: {
                sans: ['Inter', 'system-ui', 'sans-serif'],
            },
        },
    },
    plugins: [],
};
