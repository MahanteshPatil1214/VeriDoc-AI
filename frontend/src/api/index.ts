import { ApiClient } from './client'
import { ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY } from '../auth/tokenKeys'

/**
 * Shared API client for the whole app. The bearer token is read straight from
 * local storage so {@link AuthProvider} only has to write tokens there after a
 * login/refresh for every subsequent request to pick them up.
 */
export const api = new ApiClient({
  tokenProvider: {
    getAccessToken: () => localStorage.getItem(ACCESS_TOKEN_KEY),
  },
})

export { ApiClient } from './client'
export { ApiError } from './types'
export type { AuthResponse, LoginRequest, RegisterRequest, User, ApiProblem } from './types'
export { authApi } from './auth'
export const hasStoredRefreshToken = () => localStorage.getItem(REFRESH_TOKEN_KEY) !== null