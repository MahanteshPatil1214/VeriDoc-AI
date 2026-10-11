import { ApiClient } from './client'
import type { AuthResponse, LoginRequest, RegisterRequest, User } from './types'

/** Auth endpoints. Login/register are unauthenticated; {@code me} carries the token. */
export const authApi = (client: ApiClient) => ({
  register: (request: RegisterRequest) =>
    client.json<AuthResponse>('/api/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify(request),
    }),

  login: (request: LoginRequest) =>
    client.json<AuthResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify(request),
    }),

  refresh: (refreshToken: string) =>
    client.json<AuthResponse>('/api/v1/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken }),
    }),

  me: () => client.json<User>('/api/v1/auth/me'),
})