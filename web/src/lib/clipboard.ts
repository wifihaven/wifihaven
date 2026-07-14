// #2133: copy text to the clipboard with a plain-http (LAN self-host) fallback.
// The async Clipboard API only works in a secure context; self-hosted installs
// may serve the SPA over plain http on the LAN, so fall back to the legacy
// execCommand path there. Returns whether the copy succeeded.
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}
