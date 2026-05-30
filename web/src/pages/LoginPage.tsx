import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'

export function LoginPage() {
  const { login } = useAuth()
  const navigate  = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error,    setError]    = useState('')
  const [loading,  setLoading]  = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const { mustChangePassword } = await login(username, password)
      // #586: server sets must_change_password on the seeded admin row.
      // Redirect to the account page so the operator is forced to rotate
      // before reaching any other part of the UI.
      if (mustChangePassword) {
        navigate('/account')
      } else {
        navigate('/dashboard')
      }
    } catch {
      setError('Invalid username or password')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-brand-surface flex items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-brand-accent/10 border border-brand-accent/20 mb-4">
            <img src="/brand/favicon.svg" alt="" className="w-9 h-9" />
          </div>
          <h1 className="text-2xl font-bold text-brand-ink">
            Wifi<span className="text-brand-accent">Haven</span>
          </h1>
          <p className="text-brand-text-muted text-sm mt-1">Sign in to manage your network</p>
        </div>

        <form onSubmit={handleSubmit} className="bg-white rounded-2xl border border-brand-border p-6 space-y-4">
          <div>
            <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
              Username
            </label>
            <input
              type="text"
              value={username}
              onChange={e => setUsername(e.target.value)}
              className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink placeholder-brand-text-muted focus:outline-none focus:border-brand-accent transition-colors"
              placeholder="admin"
              autoFocus
              required
            />
          </div>
          <div>
            <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
              Password
            </label>
            <input
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink placeholder-brand-text-muted focus:outline-none focus:border-brand-accent transition-colors"
              placeholder="••••••••"
              required
            />
          </div>

          {error && (
            <div className="bg-red-500/10 border border-red-500/20 rounded-lg px-4 py-3 text-red-400 text-sm">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="w-full bg-brand-accent hover:bg-brand-accent-dark disabled:opacity-50 text-white font-semibold py-3 rounded-xl transition-colors"
          >
            {loading ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </div>
    </div>
  )
}
