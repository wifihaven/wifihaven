// #2903 — how many API writes (non-GET requests) are on the wire right now. The new-build reload
// on navigation (components/NewBuildBanner.tsx) waits for this to reach zero: a full page load
// cancels outstanding fetches, and in cloud a cross-origin write can still be in its CORS
// preflight, so the write itself would never be sent.
//
// A leaf module, like apiHealth, so a component can read it in tests that mock `api/client`.

type Listener = () => void

let count = 0
const listeners = new Set<Listener>()

function emit() {
  for (const l of listeners) l()
}

export const inflightWrites = {
  count(): number {
    return count
  },
  subscribe(listener: Listener): () => void {
    listeners.add(listener)
    return () => { listeners.delete(listener) }
  },
  /** Marks one write as started; call the returned function exactly once when it settles. */
  begin(): () => void {
    count += 1
    emit()
    let done = false
    return () => {
      if (done) return
      done = true
      count -= 1
      emit()
    }
  },
}
