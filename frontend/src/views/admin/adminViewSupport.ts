import { ApiClientError, isAuthenticationStatus } from '../../api/client'

export function adminFailureMessage(
  error: unknown,
  fallback: string,
  onUnauthorized: () => void,
): string {
  if (error instanceof ApiClientError && isAuthenticationStatus(error.status)) {
    onUnauthorized()
    return ''
  }
  return error instanceof ApiClientError ? error.message : fallback
}

export function formatAdminDate(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

export function sortAdminRows<T extends object>(
  rows: T[],
  key: string,
  direction: 'asc' | 'desc',
): T[] {
  const multiplier = direction === 'asc' ? 1 : -1
  return [...rows].sort((left, right) => {
    const leftValue = (left as Record<string, unknown>)[key]
    const rightValue = (right as Record<string, unknown>)[key]
    if (leftValue === rightValue) {
      return 0
    }
    if (leftValue === null || leftValue === undefined) {
      return 1 * multiplier
    }
    if (rightValue === null || rightValue === undefined) {
      return -1 * multiplier
    }
    return String(leftValue).localeCompare(String(rightValue), 'zh-CN', { numeric: true })
      * multiplier
  })
}
