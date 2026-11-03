// #2964 — the text to show for a rejected API call.
//
// A leaf module on purpose, like `api/httpError`: components import it without pulling in
// `api/client.ts`, which most page tests mock wholesale.
//
// A request that got no answer rejects with the browser's own error, not an HttpError: a
// TypeError ("Failed to fetch") when the connection fails, or an AbortError ("signal is aborted
// without reason") when `req`'s REQUEST_TIMEOUT_MS fires. Neither text tells a parent what
// happened, so both read as one plain sentence. An answer from the API keeps its own message.

export const NO_RESPONSE_MESSAGE = 'The server didn’t respond. Try again in a moment.'

export function errorMessage(e: unknown, fallback: string): string {
  if (e instanceof TypeError) return NO_RESPONSE_MESSAGE
  if (e instanceof DOMException && e.name === 'AbortError') return NO_RESPONSE_MESSAGE
  return e instanceof Error ? e.message : fallback
}
