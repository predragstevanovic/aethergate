import { useAuth } from '../context/AuthContext'
import { ROLE_RANK, type MemberRole } from '../lib/api'

/** The signed-in user's role in the current workspace. UI hints only: the API enforces every rule. */
export function useRole() {
  const { me } = useAuth()
  const role: MemberRole = me?.role ?? 'VIEWER'
  return {
    role,
    can: (min: MemberRole) => ROLE_RANK[role] >= ROLE_RANK[min],
    /** Tooltip for a disabled control. */
    needs: (min: MemberRole) => (ROLE_RANK[role] >= ROLE_RANK[min] ? undefined : `Requires the ${label(min)} role (you are ${label(role)})`),
  }
}

export function label(r: MemberRole) {
  return r.charAt(0) + r.slice(1).toLowerCase()
}
