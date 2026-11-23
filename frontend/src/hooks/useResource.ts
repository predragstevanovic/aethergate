import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../lib/api'

export interface Resource<T> {
  data: T | null
  error: ApiError | null
  loading: boolean
  refreshing: boolean
  reload: () => void
}

/**
 * Loads data for a view and keeps the previous data while refreshing, so polling and
 * filter changes never blank the screen. `intervalMs` enables live polling.
 */
export function useResource<T>(loader: () => Promise<T>, deps: unknown[], intervalMs?: number): Resource<T> {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [tick, setTick] = useState(0)
  const loaderRef = useRef(loader)
  loaderRef.current = loader
  const hasData = useRef(false)

  useEffect(() => {
    let cancelled = false
    if (hasData.current) setRefreshing(true)
    else setLoading(true)
    loaderRef.current()
      .then((d) => {
        if (cancelled) return
        hasData.current = true
        setData(d)
        setError(null)
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof ApiError ? e : new ApiError(0, { detail: String(e) }))
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false)
          setRefreshing(false)
        }
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])

  useEffect(() => {
    if (!intervalMs) return
    const id = setInterval(() => {
      if (document.visibilityState === 'visible') setTick((t) => t + 1)
    }, intervalMs)
    return () => clearInterval(id)
  }, [intervalMs])

  const reload = useCallback(() => setTick((t) => t + 1), [])
  return { data, error, loading, refreshing, reload }
}
