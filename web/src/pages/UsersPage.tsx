import { useEffect, useMemo, useState } from 'react'
import { api } from '@/api/client'
import type { CreateUserRequest, ProfileDetail, User, UserRole } from '@/types/api'
import { PageLoader } from './DashboardPage'
import { useEscapeClose } from '@/hooks/useEscapeClose'
import { EmptyState } from '@/components/EmptyState'

const ROLES: UserRole[] = ['admin', 'adult', 'child']

interface CreateForm {
  username: string
  password: string
  role: UserRole
  profileIds: number[]
}

function emptyCreateForm(): CreateForm {
  return { username: '', password: '', role: 'adult', profileIds: [] }
}

export function UsersPage() {
  const [users, setUsers] = useState<User[]>([])
  const [profiles, setProfiles] = useState<ProfileDetail[]>([])
  const [loading, setLoading] = useState(true)
  const [creating, setCreating] = useState(false)
  const [createForm, setCreateForm] = useState<CreateForm>(emptyCreateForm())
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editProfileIds, setEditProfileIds] = useState<number[]>([])
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const profileNameById = useMemo(() => {
    const m = new Map<number, string>()
    for (const p of profiles) m.set(p.profile.id, p.profile.name)
    return m
  }, [profiles])

  async function reload() {
    const [u, p] = await Promise.all([api.users.list(), api.profiles.list()])
    setUsers(u)
    setProfiles(p)
  }

  useEffect(() => {
    reload().finally(() => setLoading(false))
  }, [])

  function startCreate() {
    setCreateForm(emptyCreateForm())
    setCreating(true)
    setError(null)
  }

  function startEdit(u: User) {
    setEditingId(u.id)
    setEditProfileIds([...u.profileIds])
    setError(null)
  }

  async function saveCreate() {
    if (!createForm.username.trim()) { setError('Username is required'); return }
    if (!createForm.password) { setError('Password is required'); return }
    setSaving(true)
    setError(null)
    try {
      const body: CreateUserRequest = {
        username: createForm.username.trim(),
        password: createForm.password,
        role: createForm.role,
        profileIds: createForm.profileIds,
      }
      await api.users.create(body)
      setCreating(false)
      await reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to create user')
    } finally {
      setSaving(false)
    }
  }

  async function saveEdit() {
    if (editingId == null) return
    setSaving(true)
    setError(null)
    try {
      await api.users.setProfiles(editingId, editProfileIds)
      setEditingId(null)
      await reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to update profiles')
    } finally {
      setSaving(false)
    }
  }

  async function del(u: User) {
    if (!confirm(`Delete user "${u.username}"? This cannot be undone.`)) return
    try {
      await api.users.delete(u.id)
      await reload()
    } catch (e) {
      alert(e instanceof Error ? e.message : 'Failed to delete')
    }
  }

  if (loading) return <PageLoader />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-brand-ink">Users</h1>
        <button
          onClick={startCreate}
          className="bg-brand-accent hover:bg-brand-accent-dark text-white text-sm font-semibold px-4 py-2 rounded-xl transition-colors"
        >
          + New User
        </button>
      </div>

      <div className="bg-white rounded-2xl border border-brand-border overflow-hidden">
        {users.length === 0
          ? <EmptyState title="No users yet." />
          : users.map(u => (
              <div key={u.id} data-testid={`user-row-${u.id}`} className="flex items-center gap-4 px-5 py-4 border-b border-brand-border last:border-0">
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-brand-ink truncate">{u.username}</p>
                  <p className="text-xs text-brand-text-muted">
                    <span className={`inline-block px-2 py-0.5 rounded font-mono mr-2 ${
                      u.role === 'admin'
                        ? 'bg-brand-accent/10 text-brand-accent'
                        : u.role === 'adult'
                          ? 'bg-blue-500/10 text-blue-700'
                          : 'bg-amber-500/10 text-amber-700'
                    }`}>{u.role}</span>
                  </p>
                </div>
                <div className="hidden sm:flex flex-wrap gap-1 max-w-md justify-end">
                  {u.profileIds.length === 0
                    ? <span className="text-xs text-brand-text-muted">No profiles</span>
                    : u.profileIds.map(pid => (
                        <span key={pid} className="text-xs bg-brand-alt text-brand-text border border-brand-border-strong px-2 py-1 rounded-lg">
                          {profileNameById.get(pid) ?? `#${pid}`}
                        </span>
                      ))
                  }
                </div>
                <div className="flex gap-2 shrink-0">
                  <button
                    onClick={() => startEdit(u)}
                    className="text-xs text-brand-text hover:text-brand-ink bg-brand-alt px-3 py-1.5 rounded-lg transition-colors"
                  >Edit profiles</button>
                  <button
                    onClick={() => del(u)}
                    className="text-xs text-red-700 hover:text-red-700 bg-red-500/10 px-3 py-1.5 rounded-lg transition-colors"
                  >Delete</button>
                </div>
              </div>
            ))
        }
      </div>

      {creating && (
        <Modal title="New User" onClose={() => setCreating(false)}>
          {error && (
            <div className="bg-red-500/10 border border-red-500/30 text-red-700 text-sm rounded-xl px-4 py-2">
              {error}
            </div>
          )}
          <div>
            <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">Username</label>
            <input type="text" value={createForm.username} autoFocus
              onChange={e => setCreateForm(f => ({ ...f, username: e.target.value }))}
              className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink focus:outline-none focus:border-brand-accent" />
          </div>
          <div>
            <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">Password</label>
            <input type="password" value={createForm.password}
              onChange={e => setCreateForm(f => ({ ...f, password: e.target.value }))}
              className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink focus:outline-none focus:border-brand-accent" />
          </div>
          <div>
            <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">Role</label>
            <div className="flex gap-2">
              {ROLES.map(r => {
                const on = createForm.role === r
                return (
                  <button key={r} type="button"
                    onClick={() => setCreateForm(f => ({ ...f, role: r }))}
                    className={`text-sm px-4 py-2 rounded-lg border transition-colors ${
                      on
                        ? 'bg-brand-accent/20 text-brand-accent border-brand-accent/40'
                        : 'bg-brand-alt text-brand-text border-brand-border-strong hover:border-brand-border-strong'
                    }`}>
                    {r}
                  </button>
                )
              })}
            </div>
          </div>
          <ProfilePicker
            profiles={profiles}
            selected={createForm.profileIds}
            onChange={ids => setCreateForm(f => ({ ...f, profileIds: ids }))}
          />
          <ModalFooter
            saving={saving}
            onCancel={() => setCreating(false)}
            onSave={saveCreate}
            saveLabel="Create"
          />
        </Modal>
      )}

      {editingId !== null && (
        <Modal
          title={`Edit profiles · ${users.find(u => u.id === editingId)?.username ?? ''}`}
          onClose={() => setEditingId(null)}
        >
          {error && (
            <div className="bg-red-500/10 border border-red-500/30 text-red-700 text-sm rounded-xl px-4 py-2">
              {error}
            </div>
          )}
          <ProfilePicker
            profiles={profiles}
            selected={editProfileIds}
            onChange={setEditProfileIds}
          />
          <ModalFooter
            saving={saving}
            onCancel={() => setEditingId(null)}
            onSave={saveEdit}
            saveLabel="Save"
          />
        </Modal>
      )}
    </div>
  )
}

function ProfilePicker({
  profiles, selected, onChange,
}: {
  profiles: ProfileDetail[]
  selected: number[]
  onChange: (ids: number[]) => void
}) {
  function toggle(id: number) {
    onChange(selected.includes(id) ? selected.filter(x => x !== id) : [...selected, id])
  }
  return (
    <div>
      <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">Linked profiles</label>
      {profiles.length === 0
        ? <EmptyState variant="inline" title="No profiles available." />
        : (
          <div className="flex flex-wrap gap-2">
            {profiles.map(p => {
              const on = selected.includes(p.profile.id)
              return (
                <button key={p.profile.id} type="button" onClick={() => toggle(p.profile.id)}
                  className={`text-sm px-3 py-1.5 rounded-lg border transition-colors ${
                    on
                      ? 'bg-brand-accent/20 text-brand-accent border-brand-accent/40'
                      : 'bg-brand-alt text-brand-text border-brand-border-strong hover:border-brand-border-strong'
                  }`}>
                  {on ? '✓ ' : ''}{p.profile.name}
                </button>
              )
            })}
          </div>
        )
      }
    </div>
  )
}

function Modal({ title, children, onClose }: { title: string; children: React.ReactNode; onClose: () => void }) {
  useEscapeClose(onClose)
  return (
    <div className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4 overflow-y-auto" onClick={onClose}>
      <div
        className="bg-white rounded-2xl border border-brand-border-strong w-full max-w-lg my-8 p-6 space-y-5 max-h-[90vh] overflow-y-auto"
        onClick={e => e.stopPropagation()}
      >
        <h3 className="text-lg font-bold text-brand-ink">{title}</h3>
        {children}
      </div>
    </div>
  )
}

function ModalFooter({
  saving, onCancel, onSave, saveLabel,
}: {
  saving: boolean
  onCancel: () => void
  onSave: () => void
  saveLabel: string
}) {
  return (
    <div className="flex gap-3 pt-2">
      <button onClick={onCancel} disabled={saving}
        className="flex-1 py-3 rounded-xl bg-brand-alt text-brand-text font-medium disabled:opacity-50">
        Cancel
      </button>
      <button onClick={onSave} disabled={saving}
        className="flex-1 py-3 rounded-xl bg-brand-accent text-white font-semibold disabled:opacity-50">
        {saving ? 'Saving…' : saveLabel}
      </button>
    </div>
  )
}
