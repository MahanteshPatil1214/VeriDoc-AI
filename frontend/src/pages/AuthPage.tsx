import { useState } from 'react'
import type { FormEvent } from 'react'

import { api, authApi, ApiError } from '../api'
import { useAuth } from '../auth/useAuth'
import './AuthPage.css'

type Mode = 'login' | 'register' | 'forgot'

export function AuthPage() {
  const { login, register } = useAuth()
  const [mode, setMode] = useState<Mode>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const switchMode = (next: Mode) => {
    setMode(next)
    setError(null)
    setNotice(null)
    setPassword('')
  }

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    setNotice(null)
    setSubmitting(true)
    try {
      if (mode === 'login') {
        await login(email, password)
      } else if (mode === 'register') {
        await register(email, password, displayName)
      } else {
        await authApi(api).forgotPassword({ email, newPassword: password })
        setMode('login')
        setPassword('')
        setNotice('Password updated. Sign in with your new password.')
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Something went wrong. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="auth-page">
      <form className="auth-card" onSubmit={onSubmit}>
        <div className="auth-brand">
          <div className="auth-logo" aria-hidden="true" />
          <h1 className="auth-title">VeriDoc AI</h1>
          <p className="auth-tagline">
            Answers grounded only in your uploaded documents.
          </p>
        </div>

        <div className="auth-tabs" role="tablist" aria-label="Authentication mode">
          <button
            type="button"
            className={`auth-tab${mode === 'login' ? ' active' : ''}`}
            onClick={() => switchMode('login')}
          >
            Sign in
          </button>
          <button
            type="button"
            className={`auth-tab${mode === 'register' ? ' active' : ''}`}
            onClick={() => switchMode('register')}
          >
            Create account
          </button>
        </div>

        {mode === 'register' && (
          <label className="field">
            <span className="field-label">Display name</span>
            <input
              className="input"
              type="text"
              autoComplete="name"
              maxLength={120}
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder="Ada Lovelace"
              required
            />
          </label>
        )}

        <label className="field">
          <span className="field-label">Email</span>
          <input
            className="input"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
            required
          />
        </label>

        <label className="field">
          <span className="field-label">{mode === 'forgot' ? 'New password' : 'Password'}</span>
          <input
            className="input"
            type="password"
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            minLength={mode === 'login' ? undefined : 12}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder={mode === 'login' ? '••••••••' : 'At least 12 characters'}
            required
          />
        </label>

        {mode === 'login' && (
          <button type="button" className="auth-link" onClick={() => switchMode('forgot')}>
            Forgot password?
          </button>
        )}

        {mode === 'forgot' && (
          <p className="auth-hint">
            Enter your account email and choose a new password. If the account exists, its
            password is replaced immediately.
          </p>
        )}

        {notice && (
          <p className="form-notice" role="status">
            {notice}
          </p>
        )}

        <p className="form-error" role="alert">
          {error ?? ''}
        </p>

        <button className="btn btn-primary auth-submit" type="submit" disabled={submitting}>
          {submitting
            ? 'Please wait…'
            : mode === 'login'
              ? 'Sign in'
              : mode === 'register'
                ? 'Create account'
                : 'Update password'}
        </button>

        {mode === 'forgot' && (
          <button type="button" className="auth-link" onClick={() => switchMode('login')}>
            Back to sign in
          </button>
        )}
      </form>
    </div>
  )
}