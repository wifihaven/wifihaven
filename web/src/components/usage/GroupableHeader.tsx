interface Props {
  label: string
  groupKey: string  // "domain" | "device" | "profile"
  groupBy: string[]
  onToggle: (key: string) => void
  disabled?: boolean
  disabledReason?: string
  testIdPrefix?: string
}

// #846 — column header that doubles as a group-by toggle. Click to add/remove
// this column from the aggregation set. The active state is highlighted in
// the same emerald accent as the bucket selector.
export function GroupableHeader({
  label,
  groupKey,
  groupBy,
  onToggle,
  disabled,
  disabledReason,
  testIdPrefix,
}: Props) {
  const active = groupBy.includes(groupKey)
  const testId = `${testIdPrefix ?? 'group'}-${groupKey}`
  return (
    <button
      type="button"
      data-testid={testId}
      disabled={disabled}
      title={
        disabled
          ? disabledReason ?? ''
          : active
          ? 'Click to remove from group-by'
          : 'Click to group by this column'
      }
      onClick={() => {
        if (!disabled) onToggle(groupKey)
      }}
      className={`inline-flex items-center gap-1 ${
        disabled
          ? 'text-gray-700 cursor-not-allowed'
          : active
          ? 'text-emerald-400 hover:text-emerald-300'
          : 'text-gray-500 hover:text-gray-300'
      }`}
    >
      <span>{label}</span>
      <span className="text-[10px] leading-none">{active ? '●' : '○'}</span>
    </button>
  )
}
