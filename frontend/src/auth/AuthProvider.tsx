import { api } from '../api'
import { authApi } from '../api/auth'
import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'

import { AuthContext } from './context'
import type { AuthContextValue } from './context'
import { ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY } from './tokenKeys'
import type { User } from '../api/types'

function persist(session: { accessToken: string; refreshToken: string }): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, session.accessToken)
  localStorage.setItem(REFRESH_TOKEN_KEY, session.refreshToken)
}

function clearSession(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  const logout = useCallback(() => {
    clearSession()
    setUser(null)
  }, [])

  // Silent refresh-on-401: exchange the stored refresh token, reload the profile.
  const refreshSession = useCallback(async (): Promise<boolean> => {
    const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY)
    if (!refreshToken) {
      return false
    }
    try {
      const session = await authApi(api).refresh(refreshToken)
      persist(session)
      setUser(session.user)
      return true
    } catch {
      clearSession()
      setUser(null)
      return false
    }
  }, [])

  useEffect(() => {
    api.onUnauthorized = () => {
      void refreshSession()
    }
  }, [refreshSession])

  // Prove the stored access token on boot; refresh once if it has expired.
  useEffect(() => {
    let cancelled = false
    const boot = async (): Promise<void> => {
      const accessToken = localStorage.getItem(ACCESS_TOKEN_KEY)
      if (!accessToken) {
        setIsLoading(false)
        return
      }
      try {
        const me = await authApi(api).me()
        if (!cancelled) setUser(me)
      } catch {
        const ok = await refreshSession()
        if (!ok && !cancelled) setUser(null)
      } finally {
        if (!cancelled) setIsLoading(false)
      }
    }
    void boot()
    return () => {
      cancelled = true
    }
  }, [refreshSession])

  const login = useCallback(async (email: string, password: string) => {
    const session = await authApi(api).login({ email, password })
    persist(session)
    setUser(session.user)
  }, [])

  const register = useCallback(
    async (email: string, password: string, displayName: string) => {
      const session = await authApi(api).register({ email, password, displayName })
      persist(session)
      setUser(session.user)
    },
    [],
  )

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      isAuthenticated: user !== null,
      isLoading,
      login,
      register,
      logout,
    }),
    [user, isLoading, login, register, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}