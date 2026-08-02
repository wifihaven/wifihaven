import { useEffect, useMemo, useState } from 'react'
import { api } from '@/api/client'
import type { CreateUserRequest, ProfileDetail, User, UserRole } from '@/types/api'
import { PageLoader } from './DashboardPage'
import { useEscapeClose } from '@/hooks/useEscapeClose'
import { useDebouncedSave, mergeSaveStatus } from '@/hooks/useDebouncedSave'
import { EmptyState } from '@/components/EmptyState'
import { SaveStatusBadge } from '@/components/SaveStatusBadge'
// The server is the source of truth for the password policy (#2084's
// AuthService.MinPasswordLength); this mirror only spares the admin a round-trip, and a drift just
// means the server refuses first. Shared with the other password surfaces rather than re-typed.
import { MIN_PASSWORD_LENGTH } from '@/pages/WelcomePage'

// Set-equality for the profileIds replace-set — order from toggling is
// irrelevant, so the autosave hook should treat [1,2] and [2,1] as equal.
function sameIdSet(a: number[], b: number[]): boolean {
  if (a.length !== b.length) return false
  const s = new Set(a)
  return b.every(x => s.has(x))
}

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
  const [loadError, setLoadError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [createForm, setCreateForm] = useState<CreateForm>(emptyCreateForm())
  // Which user row is expanded for inline (autosaved) editing.
  const [editingId, setEditingId] = useState<number | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // #2576: which member the admin is setting a password for. A separate piece of state from
  // `editingId` because it is a modal with an explicit submit, not part of the autosaved row editor.
  const [pwTarget, setPwTarget] = useState<User | null>(null)

  const profileNameById = useMemo(() => {
    const m = new Map<number, string>()
    for (const p of profiles) m.set(p.profile.id, p.profile.name)
    return m
  }, [profiles])

  async function reload() {
    try {
      const [u, p] = await Promise.all([api.users.list(), api.profiles.list()])
      setUsers(u)
      setProfiles(p)
      setLoadError(null)
    } catch (e) {
      // #1191 — surface the fetch failure instead of rendering an empty list.
      setLoadError(e instanceof Error ? e.message : 'Failed to load users')
    }
  }

  useEffect(() => {
    reload().finally(() => setLoading(false))
  }, [])

  function startCreate() {
    setCreateForm(emptyCreateForm())
    setCreating(true)
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
        {users.length === 0 && loadError
          ? <EmptyState
              title="Couldn't load users."
              hint={loadError}
              action={
                <button
                  onClick={() => { setLoading(true); reload().finally(() => setLoading(false)) }}
                  className="bg-brand-accent text-white text-sm font-semibold px-3 py-1.5 rounded-lg"
                >Retry</button>
              }
            />
          : users.length === 0
          ? <EmptyState title="No users yet." />
          : users.map(u => (
              <div key={u.id} data-testid={`user-row-${u.id}`} className="px-5 py-4 border-b border-brand-border last:border-0">
                <div className="flex items-center gap-4">
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
                      onClick={() => setEditingId(id => (id === u.id ? null : u.id))}
                      aria-expanded={editingId === u.id}
                      className="text-xs text-brand-text hover:text-brand-ink bg-brand-alt px-3 py-1.5 rounded-lg transition-colors"
                    >{editingId === u.id ? 'Done' : 'Edit'}</button>
                    {/* #2576: the in-band recovery path for a member who is locked out and has no
                        email to receive a reset link. Not offered for the admin — post-#2512 the
                        household's one admin is the viewer themself, and they rotate their own
                        password on /account (or via the #2308 emailed reset). */}
                    {u.role !== 'admin' && (
                      <button
                        data-testid={`set-password-${u.id}`}
                        onClick={() => setPwTarget(u)}
                        className="text-xs text-brand-text hover:text-brand-ink bg-brand-alt px-3 py-1.5 rounded-lg transition-colors"
                      >Set password</button>
                    )}
                    <button
                      onClick={() => del(u)}
                      className="text-xs text-red-700 hover:text-red-700 bg-red-500/10 px-3 py-1.5 rounded-lg transition-colors"
                    >Delete</button>
                  </div>
                </div>
                {editingId === u.id && (
                  <UserRowEditor user={u} profiles={profiles} reload={reload} />
                )}
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

      {pwTarget && (
        <SetPasswordModal user={pwTarget} onClose={() => setPwTarget(null)} />
      )}

    </div>
  )
}

/**
 * #2576 — the admin hands a locked-out member a new password.
 *
 * Explicitly NOT autosaved, unlike every other field on this page. Autosave is the house default
 * for policy edits because they are cheap and reversible; a credential write is neither. Debounced,
 * it would fire a real password change on every typing pause, each one revoking the member's
 * sessions (#2080) and leaving whatever half-typed prefix was in the box as their password.
 *
 * The success copy is the honest description of what happened: the password the admin just chose is
 * a handoff, not a shared secret — the server re-armed `must_change_password`, so the member is
 * bounced to /account at next login and picks their own.
 */
function SetPasswordModal({ user, onClose }: { user: User; onClose: () => void }) {
  const [password, setPassword] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  async function submit() {
    if (password.length < MIN_PASSWORD_LENGTH) {
      setError(`Password must be at least ${MIN_PASSWORD_LENGTH} characters.`)
      return
    }
    setSaving(true)
    setError(null)
    try {
      await api.users.setPassword(user.id, password)
      // Clear the plaintext from component state the moment the server has it — there is no reason
      // for it to sit in the React tree behind the confirmation.
      setPassword('')
      setDone(true)
    } catch (e) {
      // Surface the server's refusal verbatim rather than claiming success (#1191 discipline).
      setError(e instanceof Error ? e.message : 'Failed to set password')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal title={`Set password for ${user.username}`} onClose={onClose}>
      {done ? (
        <>
          <p data-testid="set-password-done" className="text-sm text-brand-text">
            Password set for <span className="font-medium text-brand-ink">{user.username}</span>.
            Give it to them now — they'll be asked to change it at the next login, and this one
            stops working then.
          </p>
          <div className="flex pt-2">
            <button
              onClick={onClose}
              className="flex-1 py-3 rounded-xl bg-brand-accent text-white font-semibold"
            >Done</button>
          </div>
        </>
      ) : (
        <>
          {error && (
            <div
              data-testid="set-password-error"
              className="bg-red-500/10 border border-red-500/30 text-red-700 text-sm rounded-xl px-4 py-2"
            >
              {error}
            </div>
          )}
          <p className="text-sm text-brand-text-muted">
            For a member who is locked out and has no email address to receive a reset link. They
            will have to choose their own password the next time they sign in.
          </p>
          <div>
            <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">New password</label>
            <input
              type="password"
              autoFocus
              data-testid="set-password-input"
              value={password}
              onChange={e => setPassword(e.target.value)}
              className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink focus:outline-none focus:border-brand-accent"
            />
            <p className="text-xs text-brand-text-muted mt-2">
              At least {MIN_PASSWORD_LENGTH} characters.
            </p>
          </div>
          <div className="flex gap-3 pt-2">
            <button onClick={onClose} disabled={saving}
              className="flex-1 py-3 rounded-xl bg-brand-alt text-brand-text font-medium disabled:opacity-50">
              Cancel
            </button>
            <button onClick={submit} disabled={saving}
              data-testid="set-password-submit"
              className="flex-1 py-3 rounded-xl bg-brand-accent text-white font-semibold disabled:opacity-50">
              {saving ? 'Setting…' : 'Set password'}
            </button>
          </div>
        </>
      )}
    </Modal>
  )
}

function UserRowEditor({
  user, profiles, reload,
}: {
  user: User
  profiles: ProfileDetail[]
  reload: () => Promise<void>
}) {
  const [username, setUsername] = useState(user.username)
  const [role, setRole] = useState<UserRole>(user.role)
  const [profileIds, setProfileIds] = useState<number[]>(user.profileIds)
  useEffect(() => { setUsername(user.username) }, [user.username])
  useEffect(() => { setRole(user.role) }, [user.role])
  useEffect(() => { setProfileIds(user.profileIds) }, [user.profileIds])

  const nameSave = useDebouncedSave(
    username,
    async (next: string) => {
      const trimmed = next.trim()
      if (!trimmed) throw new Error('Username is required')
      await api.users.patch(user.id, { username: trimmed })
      await reload()
    },
    { key: user.id },
  )

  const roleSave = useDebouncedSave(
    role,
    async (next: UserRole) => {
      await api.users.patch(user.id, { role: next })
      await reload()
    },
    { key: user.id },
  )

  const profilesSave = useDebouncedSave(
    profileIds,
    async (next: number[]) => {
      await api.users.patch(user.id, { profileIds: next })
      await reload()
    },
    { key: user.id, equals: sameIdSet },
  )

  const merged = mergeSaveStatus([nameSave, roleSave, profilesSave])

  return (
    <div data-testid={`user-editor-${user.id}`} className="mt-4 pt-4 border-t border-brand-border space-y-4">
      <div className="flex items-center justify-between">
        <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider">Username</label>
        <SaveStatusBadge
          testId={`user-save-status-${user.id}`}
          status={merged.status}
          error={merged.error}
          onRetry={merged.retry}
        />
      </div>
      <input
        type="text"
        data-testid={`user-name-input-${user.id}`}
        value={username}
        onChange={e => setUsername(e.target.value)}
        className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink focus:outline-none focus:border-brand-accent"
      />
      <div>
        <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">Role</label>
        <div className="flex gap-2">
          {ROLES.map(r => {
            const on = role === r
            return (
              <button key={r} type="button"
                onClick={() => setRole(r)}
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
        selected={profileIds}
        onChange={setProfileIds}
      />
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
