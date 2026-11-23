import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../lib/api'

/** Async button state: prevents double submits and exposes the outcome. */
export function useAsyncAction<A extends unknown[], R>(action: (...args: A) => Promise<R>) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const busy = useRef(false)
  const run = useCallback(
    async (...args: A): Promise<R | undefined> => {
      if (busy.current) return undefined
      busy.current = true
      setPending(true)
      setError(null)
      try {
        return await action(...args)
      } catch (e) {
        setError(e instanceof ApiError ? e : new ApiError(0, { detail: String(e) }))
        return undefined
      } finally {
        busy.current = false
        setPending(false)
      }
    },
    [action],
  )
  return { run, pending, error, clearError: () => setError(null) }
}

export function useClipboard(resetMs = 1600) {
  const [copied, setCopied] = useState(false)
  useEffect(() => {
    if (!copied) return
    const id = setTimeout(() => setCopied(false), resetMs)
    return () => clearTimeout(id)
  }, [copied, resetMs])
  const copy = useCallback(async (text: string) => {
    await navigator.clipboard.writeText(text)
    setCopied(true)
  }, [])
  return { copied, copy }
}

export function useHotkey(key: string, handler: () => void, enabled = true) {
  useEffect(() => {
    if (!enabled) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === key) handler()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [key, handler, enabled])
}
