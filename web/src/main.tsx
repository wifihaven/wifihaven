import React from 'react'
import ReactDOM from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import App from './App'
import './index.css'
import { createQueryClient } from '@/api/queryClient'

// #2923: Inter is appended from the bundle, not linked in index.html. A stylesheet in the
// document blocks first render until it loads or fails, and a checked-out or paused device has
// fonts.googleapis.com silently dropped, so the page stayed blank until the connect timed out.
// Appended by script it doesn't block: the page renders in the fallback font and swaps to Inter
// if it arrives.
const interFont = document.createElement('link')
interFont.rel = 'stylesheet'
interFont.href = 'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&display=swap'
document.head.appendChild(interFont)

const queryClient = createQueryClient()

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>,
)
