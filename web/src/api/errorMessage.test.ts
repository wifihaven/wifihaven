import { describe, it, expect } from 'vitest'
import { HttpError } from '@/api/httpError'
import { errorMessage, NO_RESPONSE_MESSAGE } from '@/api/errorMessage'

// #2964 — a save that never got an answer must say so in words a parent can act on,
// not as the browser's raw "Failed to fetch" / "signal is aborted without reason".
describe('errorMessage', () => {
  it('says the server did not respond when fetch itself failed', () => {
    expect(errorMessage(new TypeError('Failed to fetch'), 'Failed to save')).toBe(NO_RESPONSE_MESSAGE)
  })

  it('says the server did not respond when the request timed out', () => {
    expect(errorMessage(new DOMException('signal is aborted without reason', 'AbortError'), 'Failed to save'))
      .toBe(NO_RESPONSE_MESSAGE)
  })

  it('passes the server message through when the API answered', () => {
    expect(errorMessage(new HttpError(400, 'Profile not found'), 'Failed to save')).toBe('Profile not found')
  })

  it('falls back when the rejection is not an Error', () => {
    expect(errorMessage('nope', 'Failed to save')).toBe('Failed to save')
  })
})
