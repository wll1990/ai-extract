/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    './src/pages/**/*.{js,ts,jsx,tsx,mdx}',
    './src/components/**/*.{js,ts,jsx,tsx,mdx}',
    './src/app/**/*.{js,ts,jsx,tsx,mdx}',
  ],
  theme: {
    extend: {
      colors: {
        sand: {
          1: 'var(--s1)',
          3: 'var(--s3)',
          4: 'var(--s4)',
          9: 'var(--s9)',
          11: 'var(--s11)',
          12: 'var(--s12)',
        },
        fg: {
          high: 'var(--fg-high)',
          mid: 'var(--fg-mid)',
          low: 'var(--fg-low)',
          dim: 'var(--fg-dim)',
        },
        surface: 'var(--surface)',
        'border-subtle': 'var(--border-subtle)',
        tangerine: {
          DEFAULT: 'var(--tangerine)',
          hover: 'var(--tangerine-hover)',
        },
      },
      boxShadow: {
        'card-xs': 'var(--shadow-xs)',
        'card-sm': 'var(--shadow-sm)',
        'card-md': 'var(--shadow-md)',
        'card-lg': 'var(--shadow-lg)',
        'card-xl': 'var(--shadow-xl)',
        btn: 'var(--shadow-btn)',
      },
      borderRadius: {
        '2xl': 'var(--radius-2xl)',
        '3xl': 'var(--radius-3xl)',
        '4xl': 'var(--radius-4xl)',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
};
