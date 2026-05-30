/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
      colors: {
        // Brand palette — see docs/brand.md.
        brand: {
          surface:         '#FAFAF7', // off-white page canvas
          card:            '#FFFFFF', // panels / header
          alt:             '#F2E8D5', // cream — selected/hover, badge bg
          border:          '#E5DDC9', // muted cream border
          'border-strong': '#C9BFA7', // emphasized border / divider
          text:            '#3A5A6C', // slate — primary body text
          'text-muted':    '#6B7F8A', // slate-muted — secondary / labels
          ink:             '#1F1B16', // ink — headings, strong text
          accent:          '#C2693A', // terracotta — primary accent / CTA
          'accent-dark':   '#A8582E', // darker terracotta — hover/active
        },
      },
    },
  },
  plugins: [],
}
