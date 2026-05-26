import { Link } from 'react-router-dom'
import { useAlerts } from '@/api/queries'

// Dashboard hints backed by the unified `/api/alerts` feed. Today only
// new_device rows exist; #960 adds the access_request banner alongside.

export function NewDevicesHint() {
  const { data = [] } = useAlerts()
  const newDevices = data.filter(a => a.kind === 'new_device')
  if (newDevices.length === 0) return null
  return (
    <Link
      data-testid="dashboard-new-devices-hint"
      to="/devices"
      className="block bg-yellow-500/5 border border-yellow-500/30 rounded-2xl px-5 py-3 text-sm text-yellow-200 hover:bg-yellow-500/10 transition-colors"
    >
      {newDevices.length === 1
        ? '1 new device on the network — review on the Devices page →'
        : `${newDevices.length} new devices on the network — review on the Devices page →`}
    </Link>
  )
}
