import { createClient } from '@supabase/supabase-js'

const url = import.meta.env.VITE_SUPABASE_URL as string | undefined
const key = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY as string | undefined

export const supabaseConfigured = Boolean(url && key)

/** Browser Supabase Auth client. Only the public (publishable) key ever ships to the browser. */
export const supabase = createClient(url ?? 'http://localhost:54321', key ?? 'public-anon-key', {
  auth: { persistSession: true, autoRefreshToken: true, storageKey: 'aethergate-auth' },
})
