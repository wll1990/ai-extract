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
        surface: {
          DEFAULT: 'var(--surface)',
          2: 'var(--surface-2)',
        },
        'border-subtle': 'var(--border-subtle)',
        tangerine: {
          DEFAULT: 'var(--tangerine)',
          hover: 'var(--tangerine-hover)',
        },
        foreground: 'var(--foreground)',
        'muted-foreground': 'var(--muted-foreground)',
        'muted-foreground-2': 'var(--muted-foreground-2)',
        border: 'var(--border)',
        'border-strong': 'var(--border-strong)',
        background: 'var(--background)',
        primary: {
          DEFAULT: 'var(--primary)',
          hover: 'var(--primary-hover)',
          light: 'var(--primary-light)',
        },
        success: {
          DEFAULT: 'var(--success)',
          bg: 'var(--success-bg)',
        },
        warning: {
          bg: 'var(--warning-bg)',
          text: 'var(--warning-text)',
        },
        danger: {
          DEFAULT: 'var(--danger)',
          bg: 'var(--danger-bg)',
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
