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
                // Customer surface uses a warmer accent than admin.
                'jrpay-teal': '#0f766e',
            },
            fontFamily: {
                sans: ['Inter', 'system-ui', 'sans-serif'],
            },
        },
    },
    plugins: [],
};
