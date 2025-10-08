# Zync Chat API Documentation

**Base URL:** `http://213.165.83.239:2606`

**API Version:** v1

**Current Version Prefix:** `/v1`

---

## Table of Contents

1. [Authentication Flow](#authentication-flow)
2. [Rate Limits](#rate-limits)
3. [Error Handling](#error-handling)
4. [Endpoints](#endpoints)
   - [Health & Status](#health--status)
   - [Authentication](#authentication)
   - [User Management](#user-management)
   - [Password Reset](#password-reset)

---

## Authentication Flow

The API uses JWT-based authentication with two token types:

- **Access Token**: Short-lived (15 minutes), used for API requests
- **Refresh Token**: Long-lived (30 days), used to obtain new access tokens

### Standard Flow

1. Get a private key (`/v1/auth/get-private-key`)
2. Register with the private key (`/v1/auth/register`)
3. Verify email (`/v1/auth/verify`)
4. Use access token for authenticated requests
5. Refresh tokens when access token expires (`/v1/auth/refresh-tokens`)

---

## Rate Limits

The API implements rate limiting on various endpoints to prevent abuse:

| Endpoint Category       | Limit      | Window     | Lockout  |
|-------------------------|------------|------------|----------|
| Private Key Generation  | 1 request  | 30 minutes | Per IP   |
| Login Attempts          | 5 attempts | 10 minutes | Per IP   |
| Email Verification Send | 1 request  | 20 seconds | Per User |
| Password Reset Request  | 3 requests | 5 minutes  | Per IP   |

**Rate Limit Headers:**
- `X-Request-ID`: Unique identifier for each request
- `X-Environment`: Current environment (development/staging/production)

**Rate Limit Response:**
```json
{
  "error": "Too many login attempts. Try again later.",
  "request_id": "abc123",
  "environment": "production"
}
```

---

## Error Handling

All error responses follow this structure:

```json
{
  "error": "Error description",
  "request_id": "abc123",
  "environment": "production"
}
```

### Common HTTP Status Codes

- `200 OK`: Successful request
- `400 Bad Request`: Invalid request data
- `401 Unauthorized`: Invalid or expired authentication
- `404 Not Found`: Resource not found
- `422 Unprocessable Entity`: Validation error
- `429 Too Many Requests`: Rate limit exceeded
- `500 Internal Server Error`: Server error

### Validation Error Response

```json
{
  "error": "Validation Error",
  "message": "Request data validation failed",
  "details": [
    {
      "loc": ["body", "password"],
      "msg": "ensure this value has at least 8 characters",
      "type": "value_error.any_str.min_length"
    }
  ],
  "request_id": "abc123",
  "environment": "production"
}
```

---

## Endpoints

### Health & Status

#### GET `/`
Root endpoint with API information.

**Response:**
```json
{
  "name": "Zync Chat API",
  "version": "1.0.0",
  "environment": "production",
  "status": "running",
  "current_version": "v1",
  "documentation": {
    "swagger_ui": null,
    "redoc": null
  },
  "endpoints": {
    "health": "/v1/health",
    "api_base": "/v1/"
  },
  "timestamp": "2025-10-01T12:00:00.000Z"
}
```

#### GET `/v1/health`
Comprehensive health check endpoint.

**Response:**
```json
{
  "status": "healthy",
  "environment": "production",
  "timestamp": "2025-10-01T12:00:00.000Z",
  "version": "1.0.0",
  "api_version": "v1",
  "uptime_seconds": 3600.5,
  "services": {
    "database": {
      "status": "healthy",
      "database": "postgresql",
      "connection_time_ms": 12.5,
      "user_count": 150,
      "pool_info": {
        "pool_size": 5,
        "checked_out_connections": 2,
        "overflow_connections": 0
      }
    },
    "email": {
      "status": "ok",
      "service": "smtp",
      "backend": "smtp",
      "workers": 5,
      "queue_sizes": {
        "priority_1": 0,
        "priority_2": 3,
        "priority_3": 1
      }
    }
  }
}
```

---

### Authentication

#### GET `/v1/auth/get-private-key`
Generate a new private key for registration.

**Rate Limit:** 1 request per 30 minutes per IP

**Response:**
```json
{
  "private_key": "aBcDeF1234567890XyZ1"
}
```

---

#### POST `/v1/auth/register`
Register a new user account.

**Request Body:**
```json
{
  "username": "john_doe",
  "password": "SecurePass123",
  "email": "john@example.com",
  "private_key": "aBcDeF1234567890XyZ1"
}
```

**Validation Rules:**
- `username`: 3-20 characters, alphanumeric with hyphens/underscores, case-insensitive
- `password`: Minimum 8 characters
- `email`: Valid email address
- `private_key`: Exactly 20 characters, must be unused

**Response:**
```json
{
  "password_hash": "salt:hash...",
  "access_token": "eyJhbGc...",
  "refresh_token": "eyJhbGc...",
  "user_id": 42,
  "message": "Registration successful"
}
```

**Notes:**
- Username will be assigned a discriminator (e.g., `john_doe#1234`)
- Email verification code is automatically sent
- Private key is marked as used and cannot be reused

---

#### POST `/v1/auth/verify`
Verify email address with code.

**Request Body:**
```json
{
  "access_token": "eyJhbGc...",
  "email_code": "123456"
}
```

**Validation Rules:**
- `email_code`: Exactly 6 digits
- Code expires after 15 minutes

**Success Response:**
```json
{
  "status": "verified",
  "message": "Email address verified successfully"
}
```

**Wrong Code Response:**
```json
{
  "status": "wrong_code",
  "message": "Invalid or expired verification code"
}
```

---

#### POST `/v1/auth/send-email`
Resend email verification code.

**Rate Limit:** 1 request per 20 seconds per user

**Request Body:**
```json
{
  "access_token": "eyJhbGc..."
}
```

**Response:**
```json
{
  "status": "success",
  "message": "Verification code sent to your email"
}
```

---

#### POST `/v1/auth/login`
Login with credentials.

**Rate Limit:** 5 attempts per 10 minutes per IP (resets on successful login)

**Request Body:**
```json
{
  "private_key": "aBcDeF1234567890XyZ1",
  "username": "john_doe#1234",
  "password": "SecurePass123"
}
```

**Response:**
```json
{
  "password_hash": "salt:hash...",
  "access_token": "eyJhbGc...",
  "refresh_token": "eyJhbGc...",
  "user_id": 42,
  "message": "Login successful"
}
```

**Notes:**
- Requires valid private key (doesn't need to be the original registration key)
- Username must include discriminator
- Failed attempts count toward rate limit

---

#### POST `/v1/auth/validate-tokens`
Validate access and refresh tokens.

**Request Body:**
```json
{
  "access_token": "eyJhbGc...",
  "refresh_token": "eyJhbGc..."
}
```

**Response:**
```json
{
  "access_token_status": "valid",
  "refresh_token_status": "valid",
  "expires_at": "2025-10-01T12:15:00.000Z"
}
```

**Possible Status Values:**
- `valid`: Token is valid and not expired
- `invalid`: Token is invalid or expired

---

#### POST `/v1/auth/refresh-tokens`
Refresh access token using refresh token.

**Request Body:**
```json
{
  "user_id": 42,
  "refresh_token": "eyJhbGc..."
}
```

**Response:**
```json
{
  "access_token": "eyJhbGc...",
  "expires_at": "2025-10-01T12:15:00.000Z",
  "message": "Tokens refreshed successfully"
}
```

---

#### POST `/v1/auth/check-username`
Check username availability and get suggestion.

**Request Body:**
```json
{
  "username": "john_doe"
}
```

**Response:**
```json
{
  "available": true,
  "suggested_username": "john_doe#5678",
  "message": "Username 'john_doe#5678' is available"
}
```

**Notes:**
- Always returns a suggested username with discriminator
- The suggested username is guaranteed to be unique

---

#### POST `/v1/auth/change-password`
Change user password.

**Request Body:**
```json
{
  "access_token": "eyJhbGc...",
  "old_password": "OldPass123",
  "new_password": "NewSecurePass456"
}
```

**Response:**
```json
{
  "message": "Password changed successfully",
  "note": "Please log in again with your new password"
}
```

**Notes:**
- All refresh tokens are revoked after password change
- User must log in again with new password

---

#### POST `/v1/auth/logout`
Logout and revoke all user tokens.

**Request Body:**
```json
{
  "access_token": "eyJhbGc..."
}
```

**Response:**
```json
{
  "message": "Logged out successfully",
  "revoked_tokens": 3
}
```

---

#### POST `/v1/auth/logout-all`
Alias for `/v1/auth/logout` - logs out from all devices.

Same request/response as logout endpoint.

---

### Password Reset

#### POST `/v1/auth/forgot-password`
Initiate password reset process.

**Rate Limit:** 3 requests per 5 minutes per IP

**Request Body:**
```json
{
  "email": "john@example.com"
}
```

**Response:**
```json
{
  "message": "If an account with this email exists, you will receive reset instructions",
  "request_id": "abc123"
}
```

**Notes:**
- Always returns the same message regardless of email existence (security)
- Reset token expires after 1 hour
- Email contains reset link with token

---

#### POST `/v1/auth/validate-reset-token`
Validate password reset token before use.

**Request Body:**
```json
{
  "reset_token": "eyJhbGc..."
}
```

**Valid Token Response:**
```json
{
  "valid": true,
  "expires_at": "2025-10-01T13:00:00.000Z",
  "message": "Reset token is valid"
}
```

**Invalid Token Response:**
```json
{
  "valid": false,
  "message": "Invalid or expired reset token"
}
```

---

#### POST `/v1/auth/reset-password`
Reset password using reset token.

**Request Body:**
```json
{
  "reset_token": "eyJhbGc...",
  "new_password": "NewSecurePass456"
}
```

**Password Requirements:**
- Minimum 8 characters
- At least one digit
- At least one uppercase letter
- At least one lowercase letter

**Response:**
```json
{
  "message": "Password has been reset successfully",
  "login_required": true
}
```

**Notes:**
- Reset token is marked as used and cannot be reused
- All refresh tokens are revoked
- All other reset tokens for the user are revoked
- User must log in with new password

---

### User Management

#### POST `/v1/users/me`
Get current user information.

**Request Body:**
```json
{
  "access_token": "eyJhbGc...",
  "user_id": 42
}
```

**Response:**
```json
{
  "username": "john_doe#1234",
  "email": "john@example.com",
  "email_verified": true,
  "created_at": "2025-09-15T10:30:00.000Z",
  "last_login": "2025-10-01T11:45:00.000Z"
}
```

---

#### GET `/v1/users/info`
Get public user information.

**Query Parameters:**
- `user_id` (required): User ID

**Example:** `/v1/users/info?user_id=42`

**Response:**
```json
{
  "username": "john_doe#1234"
}
```

---

## Token Expiration Times

| Token Type | Duration | Notes |
|-----------|----------|-------|
| Access Token | 15 minutes | Used for API authentication |
| Refresh Token | 30 days | Used to get new access tokens |
| Email Verification Code | 15 minutes | Six-digit numeric code |
| Password Reset Token | 1 hour | Single-use token |
| Private Key | No expiration | Single-use, cleaned up after 7 days if unused |

---

## Security Features

1. **JWT Tokens**: All tokens include environment validation
2. **Password Hashing**: PBKDF2-HMAC-SHA256 with salt
3. **Rate Limiting**: IP-based and user-based rate limits
4. **Token Revocation**: Refresh tokens can be revoked
5. **Email Verification**: Required for full account access
6. **Password Reset**: Secure token-based password reset
7. **Private Keys**: One-time use keys for registration/login
8. **Automatic Cleanup**: Expired tokens and codes are automatically cleaned up every 15 minutes

---

## Database Cleanup

The API automatically performs cleanup every 15 minutes:
- Expired email verification codes
- Expired rate limits
- Expired refresh tokens
- Expired password reset tokens
- Old unused private keys (7+ days)