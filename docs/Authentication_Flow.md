# Tungsten Authentication Flow Documentation

## Overview

Tungsten implements a secure authentication system that integrates with the Zync Chat API. The system uses encrypted local storage for tokens and requires single-use private keys for all login/registration operations.

For a detailed understanding of all the endpoints, please refer to the [Central documentation](Central.md)

---

## Token Management

### Token Types

1. **Access Token**
   - Lifetime: 15 minutes
   - Usage: All authenticated API requests
   - Storage: Encrypted local storage (platform-specific)

2. **Refresh Token**
   - Lifetime: 30 days
   - Usage: Obtaining new access tokens
   - Storage: Encrypted local storage (platform-specific)

3. **Private Key**
   - Lifetime: 7 days (if unused)
   - Usage: **Single-use only** for login/registration
   - Once used: Immediately marked as invalid on server

---

## Login Flow

### Step-by-Step Process

1. **Obtain Private Key**
   ```javascript
   GET http://213.165.83.239:2606/v1/auth/get-private-key
   Response: { "private_key": "aBcDeF1234567890XyZ1" }
   ```
   - Rate limited: 1 request per 30 minutes per IP
   - Key is valid for 7 days if unused
   - Key becomes invalid once used

2. **Authenticate with Credentials**
   ```javascript
   POST http://213.165.83.239:2606/v1/auth/login
   Body: {
     "private_key": "aBcDeF1234567890XyZ1",
     "username": "user#1234",
     "password": "SecurePass123"
   }
   ```
   - Private key is marked as USED on server
   - Returns access_token and refresh_token

3. **Store Tokens Securely**
   - Tokens are encrypted using platform-specific storage:
     - **Windows**: DPAPI (Data Protection API)
     - **macOS**: Keychain
     - **Linux**: Secret Service API / libsecret
   - Fallback: File-based encryption

4. **Load User Information**
   - Fetch complete user profile
   - Update local encrypted storage

---

## Logout Flow

### Comprehensive Token Clearing Process

When a user logs out, the following occurs:

#### 1. Server-Side Token Revocation
```javascript
POST http://213.165.83.239:2606/v1/auth/logout
Body: { "access_token": "eyJhbGc..." }
```
- All refresh tokens for the user are revoked
- Access token is invalidated
- Server logs the logout event

#### 2. Local Session Clearing
```javascript
POST /api/session/clear (Local Java Backend)
```
- Access token removed from encrypted storage
- Refresh token removed from encrypted storage
- User data removed from encrypted storage
- Session metadata removed from encrypted storage

#### 3. Memory Cleanup
```javascript
this.tokens.access = null;
this.tokens.refresh = null;
this.currentUser = null;
```

### Important Notes About Logout

⚠️ **CRITICAL**: After logout, the user **MUST** obtain a new private key to log in again.

- The previous private key (if any) is already used and invalid
- Private keys are **SINGLE-USE ONLY**
- A new private key must be requested from the API
- Rate limit applies: 1 private key per 30 minutes per IP

---

## Registration Flow

### Step-by-Step Process

1. **Obtain Private Key** (same as login)
2. **Register New Account**
   ```javascript
   POST http://213.165.83.239:2606/v1/auth/register
   Body: {
     "username": "john_doe",
     "password": "SecurePass123",
     "email": "john@example.com",
     "private_key": "aBcDeF1234567890XyZ1"
   }
   ```
3. **Store Tokens** (encrypted local storage)
4. **Email Verification Required**
   - 6-digit code sent to email
   - Code valid for 15 minutes

---

## Password Change Flow

When a user changes their password:

1. **Change Password Request**
   ```javascript
   POST http://213.165.83.239:2606/v1/auth/change-password
   Body: {
     "access_token": "eyJhbGc...",
     "old_password": "OldPass123",
     "new_password": "NewSecurePass456"
   }
   ```

2. **All Tokens Revoked**
   - Server revokes all refresh tokens
   - User is automatically logged out

3. **Re-login Required**
   - User must obtain a NEW private key
   - User must log in with new password

---

## Token Refresh Flow

When the access token expires (after 15 minutes):

```javascript
POST http://213.165.83.239:2606/v1/auth/refresh-tokens
Body: {
  "user_id": 42,
  "refresh_token": "eyJhbGc..."
}
Response: {
  "access_token": "eyJhbGc...",
  "expires_at": "2025-10-01T12:15:00.000Z"
}
```

- New access token is valid for another 15 minutes
- Refresh token remains valid (30 days from initial login)
- Local encrypted storage is updated with new access token

---

## Security Features

### Encrypted Local Storage

**Platform-Specific Encryption:**
- **Windows**: DPAPI with user/machine scope
- **macOS**: Keychain Services
- **Linux**: Secret Service API (GNOME Keyring, KWallet)
- **Fallback**: AES-256 file-based encryption

### Logging & Monitoring

All authentication events are logged:
- Login success/failure
- Registration success/failure
- Email verification
- Token refresh
- Password changes
- Logout events
- Security violations

### Rate Limiting

| Operation               | Limit      | Window     | Scope    |
|-------------------------|------------|------------|----------|
| Private Key Generation  | 1 request  | 30 minutes | Per IP   |
| Login Attempts          | 5 attempts | 10 minutes | Per IP   |
| Email Verification Send | 1 request  | 20 seconds | Per User |
| Password Reset Request  | 3 requests | 5 minutes  | Per IP   |

---

## Console Logging

### Login Process Logs
```
=== LOGIN PROCESS STARTED ===
Obtaining new private key from server
- Private key obtained successfully
- Private key is single-use and valid for 7 days if unused
Authenticating with credentials
- Authentication successful
- Private key has been marked as USED on server
Storing tokens in encrypted local storage
- Tokens stored securely (Access: 15min, Refresh: 30 days)
Loading user information
=== LOGIN COMPLETE ===
```

### Logout Process Logs
```
=== LOGOUT INITIATED ===
- Clearing all authentication tokens
- Access token will be invalidated
- Refresh token will be revoked on server
- Note: A new private key will be required for next login
- Server revoked 3 tokens
- Clearing encrypted local session storage
- Local session cleared successfully
=== LOGOUT COMPLETE ===
You will need to login again
```

---

## Error Handling

### Common Scenarios

1. **Invalid Private Key**
   - Error: "Invalid or already used private key"
   - Solution: Request a new private key

2. **Expired Access Token**
   - Automatically refresh using refresh token
   - If refresh token also expired, logout and require re-login

3. **Rate Limit Exceeded**
   - Error: "Too many requests. Try again later."
   - Wait for rate limit window to expire

4. **Network Error**
   - Retry with exponential backoff
   - Show user-friendly error message

---

## Best Practices

1. **Never reuse private keys** - They are single-use only
2. **Always clear tokens on logout** - Prevents security issues
3. **Validate tokens before API calls** - Reduces failed requests
4. **Monitor token expiration** - Refresh before expiration
5. **Log all auth events** - Helps with debugging and security monitoring

---

## API Endpoints Reference

| Endpoint                   | Method | Purpose                  |
|----------------------------|--------|--------------------------|
| `/v1/auth/get-private-key` | GET    | Obtain new private key   |
| `/v1/auth/login`           | POST   | Login with credentials   |
| `/v1/auth/register`        | POST   | Register new account     |
| `/v1/auth/logout`          | POST   | Logout and revoke tokens |
| `/v1/auth/validate-tokens` | POST   | Validate token status    |
| `/v1/auth/refresh-tokens`  | POST   | Get new access token     |
| `/v1/auth/change-password` | POST   | Change user password     |
| `/v1/users/me`             | POST   | Get current user info    |

---

## Local Backend Endpoints

| Endpoint                    | Method | Purpose                 |
|-----------------------------|--------|-------------------------|
| `/api/session/store`        | POST   | Store encrypted session |
| `/api/session/load`         | GET    | Load encrypted session  |
| `/api/session/update-token` | POST   | Update access token     |
| `/api/session/update-user`  | POST   | Update user data        |
| `/api/session/clear`        | POST   | Clear all session data  |
| `/api/session/stats`        | GET    | Get session statistics  |

---

## Troubleshooting

### "Failed to get private key"
- Check network connectivity
- Verify rate limit hasn't been exceeded (1 per 30 min)
- Check API server status

### "Session cleared from secure storage"
- Normal during logout
- Session will be restored on next login
- No action needed

### "Private key already used"
- Request a new private key
- Each key can only be used once

### "Token validation failed"
- Tokens may be expired
- Logout and login again with new private key

---

*Last Updated: 2025-10-07*