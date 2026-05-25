import { useEffect } from 'react'

// Module-level stack so nested modals close one layer at a time:
// the most recently mounted handler runs on Escape, and others stay inert
// until it unregisters.
const stack: Array<() => void> = []

let installed = false
function installListener() {
  if (installed) return
  installed = true
  window.addEventListener('keydown', e => {
    if (e.key !== 'Escape' || stack.length === 0) return
    e.stopPropagation()
    stack[stack.length - 1]()
  })
}

export function useEscapeClose(onClose: () => void, enabled = true) {
  useEffect(() => {
    if (!enabled) return
    installListener()
    stack.push(onClose)
    return () => {
      const i = stack.lastIndexOf(onClose)
      if (i >= 0) stack.splice(i, 1)
    }
  }, [onClose, enabled])
}
