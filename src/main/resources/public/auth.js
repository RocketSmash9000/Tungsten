// Tungsten Authentication System with Secure Local Storage
// noinspection D

class TungstenAuth {
    constructor() {
        this.baseURL = 'http://213.165.83.239:2606/v1';
        this.localAPI = ''; // Local backend API (same origin)
        this.currentUser = null;
        this.tokens = {
            access: null,
            refresh: null
        };
        this.cachedPrivateKey = null; // Cache private key to avoid rate limits

        this.init();
    }

    init() {
        this.bindEvents();
        this.checkAuthStatus();
    }

    // Bind all event listeners
    bindEvents() {
        // Form submissions
        document.getElementById('login').addEventListener('submit', (e) => this.handleLogin(e));
        document.getElementById('register').addEventListener('submit', (e) => this.handleRegister(e));
        document.getElementById('verification').addEventListener('submit', (e) => this.handleVerification(e));
        document.getElementById('forgotPassword').addEventListener('submit', (e) => this.handleForgotPassword(e));
        document.getElementById('changePassword').addEventListener('submit', (e) => this.handleChangePassword(e));

        // Navigation links
        document.getElementById('showRegister').addEventListener('click', (e) => {
            e.preventDefault();
            this.showForm('registerForm');
        });
        document.getElementById('showLogin').addEventListener('click', (e) => {
            e.preventDefault();
            this.showForm('loginForm');
        });
        document.getElementById('showForgotPassword').addEventListener('click', (e) => {
            e.preventDefault();
            this.showForm('forgotPasswordForm');
        });
        document.getElementById('backToLogin').addEventListener('click', (e) => {
            e.preventDefault();
            this.showForm('loginForm');
        });
        document.getElementById('backToLoginFromForgot').addEventListener('click', (e) => {
            e.preventDefault();
            this.showForm('loginForm');
        });

        // Action buttons
        document.getElementById('resendCode').addEventListener('click', (e) => this.handleResendCode(e));
        document.getElementById('logoutBtn').addEventListener('click', () => this.handleLogout());
        document.getElementById('changePasswordBtn').addEventListener('click', () => this.showChangePasswordModal());
        document.getElementById('closeChangePassword').addEventListener('click', () => this.hideChangePasswordModal());
        document.getElementById('cancelChangePassword').addEventListener('click', () => this.hideChangePasswordModal());

        // Message close button
        document.getElementById('closeMessage').addEventListener('click', () => this.hideMessage());

        // API test button
        document.getElementById('testBtn').addEventListener('click', () => this.testAPI());

        // Password confirmation validation
        document.getElementById('confirmPassword').addEventListener('input', () => this.validatePasswordMatch());
        document.getElementById('confirmNewPassword').addEventListener('input', () => this.validateNewPasswordMatch());
    }

    // Check if user is already authenticated using secure local storage
    async checkAuthStatus() {
        try {
            const sessionData = await this.loadLocalSession();
            if (sessionData && sessionData.status === 'success') {
                this.tokens.access = sessionData.access_token;
                this.tokens.refresh = sessionData.refresh_token;
                this.currentUser = sessionData.user_data;

                // Validate tokens with remote API
                const isValid = await this.validateTokens();
                if (isValid) {
                    // If user data looks complete, just update display and skip API call
                    if (this.currentUser && this.currentUser.email && this.currentUser.username) {
                        console.log('Session restored with complete user data');
                        this.updateUserDisplay();
                        this.showDashboard();
                        this.showMessage('Welcome back! Session restored securely.', 'success');
                    } else {
                        // User data incomplete, fetch from API
                        console.log('Fetching complete user data from API');
                        await this.loadUserInfo();
                        this.showDashboard();
                        this.showMessage('Welcome back! Session restored securely.', 'success');
                    }
                    return;
                } else {
                    // Tokens are invalid, clear local session
                    await this.clearLocalSession();
                }
            }
        } catch (error) {
            console.error('Session restoration failed:', error);
            await this.clearLocalSession();
        }

        this.showForm('loginForm');
    }

    // Secure Local Storage Methods
    async storeLocalSession(accessToken, refreshToken, userData) {
        try {
            const response = await fetch(`${this.localAPI}/api/session/store`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    access_token: accessToken,
                    refresh_token: refreshToken,
                    user_id: userData.user_id || userData.userId,
                    username: userData.username,
                    email: userData.email,
                    email_verified: userData.email_verified || userData.emailVerified,
                    created_at: userData.created_at || userData.createdAt,
                    last_login: userData.last_login || userData.lastLogin
                })
            });

            const result = await response.json();
            if (response.ok) {
                console.log('Session stored securely:', result.store_type);
                return result;
            } else {
                throw new Error(result.message || 'Failed to store session');
            }
        } catch (error) {
            console.error('Failed to store session locally:', error);
            throw error;
        }
    }

    async loadLocalSession() {
        try {
            const response = await fetch(`${this.localAPI}/api/session/load`);
            const result = await response.json();

            if (response.ok && result.status === 'success') {
                console.log('Session loaded from secure storage');
                return result;
            } else if (result.status === 'no_session') {
                console.log('No stored session found');
                return null;
            } else {
                throw new Error(result.message || 'Failed to load session');
            }
        } catch (error) {
            console.error('Failed to load session locally:', error);
            return null;
        }
    }
    async updateLocalUserData(userData) {
        try {
            const response = await fetch(`${this.localAPI}/api/session/update-user`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    user_id: userData.user_id || userData.userId,
                    username: userData.username,
                    email: userData.email,
                    email_verified: userData.email_verified || userData.emailVerified,
                    created_at: userData.created_at || userData.createdAt,
                    last_login: userData.last_login || userData.lastLogin
                })
            });

            const result = await response.json();
            if (response.ok) {
                this.currentUser = userData;
                console.log('User data updated in secure storage');
                return result;
            } else {
                throw new Error(result.message || 'Failed to update user data');
            }
        } catch (error) {
            console.error('Failed to update user data locally:', error);
            throw error;
        }
    }

    async clearLocalSession() {
        try {
            const response = await fetch(`${this.localAPI}/api/session/clear`, {
                method: 'POST'
            });

            const result = await response.json();
            if (response.ok) {
                this.tokens.access = null;
                this.tokens.refresh = null;
                this.currentUser = null;
                console.log('Session cleared from secure storage');
                return result;
            } else {
                throw new Error(result.message || 'Failed to clear session');
            }
        } catch (error) {
            console.error('Failed to clear session locally:', error);
            // Don't throw error as this might be called during cleanup
        }
    }
// Validate stored tokens
    async validateTokens() {
        try {
            const response = await fetch(`${this.baseURL}/auth/validate-tokens`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    access_token: this.tokens.access,
                    refresh_token: this.tokens.refresh
                })
            });

            if (response.ok) {
                const data = await response.json();
                
                // If refresh token is valid but access token is expired, refresh it
                if (data.refresh_token_status === 'valid' && data.access_token_status === 'invalid') {
                    console.log('Access token expired, refreshing with refresh token...');
                    return await this.refreshAccessToken();
                }
                
                // Both tokens valid
                if (data.access_token_status === 'valid' && data.refresh_token_status === 'valid') {
                    return true;
                }
                
                // Refresh token is invalid/expired, session is dead
                return false;
            }
            return false;
        } catch (error) {
            console.error('Token validation error:', error);
            return false;
        }
    }

    // Refresh access token using refresh token
    async refreshAccessToken() {
        try {
            console.log('Refreshing access token...');
            const response = await fetch(`${this.baseURL}/auth/refresh-tokens`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    user_id: this.currentUser.user_id || this.currentUser.userId,
                    refresh_token: this.tokens.refresh
                })
            });

            if (response.ok) {
                const data = await response.json();
                console.log('Access token refreshed successfully');
                
                // Update the access token in memory (don't store in secure storage - it's short-lived)
                this.tokens.access = data.access_token;
                
                // Note: We keep the access token in memory only, as it expires in 15 minutes
                // The refresh token remains in secure storage and is used to get new access tokens
                console.log('New access token obtained (valid for 15 minutes)');
                return true;
            } else {
                const errorData = await response.json();
                console.error('Failed to refresh access token:', errorData.error);
                return false;
            }
        } catch (error) {
            console.error('Token refresh error:', error);
            return false;
        }
    }

    // Load current user information
    async loadUserInfo() {
        try {
            // Debug: Log what we're sending
            /*
            const requestBody = {
                access_token: this.tokens.access,
                user_id: this.currentUser.user_id || this.currentUser.userId
            };
            console.log('Requesting user info with:', {
                user_id: requestBody.user_id,
                user_id_type: typeof requestBody.user_id,
                access_token_present: !!requestBody.access_token
            });
             */

            const response = await fetch(`${this.baseURL}/users/me`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(requestBody)
            });

            if (response.status === 401 || response.status === 422) {
                // Access token might be expired, try refreshing
                console.log(`User info request failed (${response.status}), attempting to refresh token...`);
                const refreshed = await this.refreshAccessToken();
                
                if (refreshed) {
                    // Retry the request with new access token
                    const retryBody = {
                        access_token: this.tokens.access,
                        user_id: this.currentUser.user_id || this.currentUser.userId
                    };
                    console.log('Retrying user info request with refreshed token');

                    const retryResponse = await fetch(`${this.baseURL}/users/me`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(retryBody)
                    });

                    if (retryResponse.ok) {
                        const userData = await retryResponse.json();
                        // Keep the user_id from the original userData
                        userData.user_id = this.currentUser.user_id || this.currentUser.userId;
                        this.currentUser = userData;
                        await this.updateLocalUserData(userData);
                        this.updateUserDisplay();
                        console.log('User info loaded successfully after token refresh');
                        return;
                    } else {
                        const errorData = await retryResponse.json();
                        console.error('Retry failed:', errorData);
                    }
                }
                
                // If refresh failed or retry failed, clear session
                console.error('Failed to load user info after token refresh');
                return;
            }

            if (response.ok) {
                const userData = await response.json();
                // Keep the user_id from the original userData
                userData.user_id = this.currentUser.user_id || this.currentUser.userId;
                this.currentUser = userData;
                await this.updateLocalUserData(userData);
                this.updateUserDisplay();
                console.log('User info loaded successfully');
            } else {
                const errorData = await response.json();
                console.error('Failed to load user info:', errorData);
            }
        } catch (error) {
            console.error('Failed to load user info:', error);
        }
    }

    // Handle login form submission
    async handleLogin(e) {
        e.preventDefault();
        this.showLoading();

        const formData = new FormData(e.target);
        const username = formData.get('username');
        const password = formData.get('password');

        try {
            console.log('=== LOGIN PROCESS STARTED ===');
            console.log('Obtaining new private key from server');

            // Get a private key (cached or new)
            const privateKey = await this.getPrivateKey();
            if (!privateKey) {
                console.error('Failed to obtain private key');
                this.showMessage('Failed to get private key. Please try again later (rate limit: 1 per 30 min).', 'error');
                return;
            }

            console.log('- Private key obtained successfully');
            console.log('- Private key is single-use and valid for 7 days if unused');
            console.log('Authenticating with credentials');

            // Then attempt login
            const response = await fetch(`${this.baseURL}/auth/login`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    private_key: privateKey,
                    username: username,
                    password: password
                })
            });

            const data = await response.json();

            if (response.ok) {
                console.log('- Authentication successful');
                console.log('- Private key has been marked as USED on server');

                // Clear cached private key after successful use
                this.cachedPrivateKey = null;

                console.log('Storing tokens in encrypted local storage');

                // Store tokens and user data securely
                const userData = {
                    user_id: data.user_id,
                    username: username,
                    email: '', // Will be loaded from user info
                    email_verified: false
                };

                await this.storeLocalSession(data.access_token, data.refresh_token, userData);

                this.tokens.access = data.access_token;
                this.tokens.refresh = data.refresh_token;
                this.currentUser = userData; // Set currentUser before calling loadUserInfo

                console.log('- Tokens stored securely (Access: 15min, Refresh: 30 days)');
                console.log('Loading user information');

                await this.loadUserInfo();

                if (this.currentUser && !this.currentUser.email_verified) {
                    console.log('- Email verification required');
                    this.showMessage('Please verify your email address.', 'info');
                    this.showForm('verificationForm');
                } else {
                    console.log('=== LOGIN COMPLETE ===');
                    this.showMessage('Login successful! Session stored securely.', 'success');
                    this.showDashboard();
                }
            } else {
                console.error('Authentication failed:', data.error);

                // If private key was invalid/used, clear it
                if (data.error && (data.error.includes('private key') || data.error.includes('Private key'))) {
                    console.log('- Clearing invalid private key from cache');
                    this.cachedPrivateKey = null;
                }

                this.showMessage(data.error || 'Login failed', 'error');
            }
        } catch (error) {
            console.error('Login error:', error);
            this.showMessage('Network error. Please try again.', 'error');
        } finally {
            this.hideLoading();
        }
    }

    // Handle registration form submission
    async handleRegister(e) {
        e.preventDefault();

        if (!this.validatePasswordMatch()) {
            return;
        }

        this.showLoading();

        const formData = new FormData(e.target);
        const username = formData.get('username');
        const email = formData.get('email');
        const password = formData.get('password');

        try {
            console.log('=== REGISTRATION PROCESS STARTED ===');
            console.log('Obtaining private key');

            // Get a private key (cached or new)
            const privateKey = await this.getPrivateKey();
            if (!privateKey) {
                this.showMessage('Failed to get private key. Please try again later (rate limit: 1 per 30 min).', 'error');
                return;
            }

            console.log('- Private key obtained successfully');
            console.log('Registering account');

            // Then register
            const response = await fetch(`${this.baseURL}/auth/register`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    username: username,
                    password: password,
                    email: email,
                    private_key: privateKey
                })
            });

            const data = await response.json();

            if (response.ok) {
                console.log('- Registration successful');
                console.log('- Private key has been marked as USED on server');

                // Clear cached private key after successful use
                this.cachedPrivateKey = null;

                console.log('Storing tokens in encrypted local storage');

                // Store session data securely
                const userData = {
                    user_id: data.user_id,
                    username: username,
                    email: email,
                    email_verified: false
                };

                await this.storeLocalSession(data.access_token, data.refresh_token, userData);

                this.tokens.access = data.access_token;
                this.tokens.refresh = data.refresh_token;
                this.currentUser = userData; // Set currentUser before calling loadUserInfo

                await this.loadUserInfo();
                console.log('=== REGISTRATION COMPLETE ===');
                this.showMessage('Registration successful! Please verify your email. Session stored securely.', 'success');
                this.showForm('verificationForm');
            } else {
                // If private key was invalid/used, clear it
                if (data.error && (data.error.includes('private key') || data.error.includes('Private key'))) {
                    console.log('- Clearing invalid private key from cache');
                    this.cachedPrivateKey = null;
                }

                if (data.details) {
                    const errorMsg = data.details.map(d => d.msg).join(', ');
                    this.showMessage(errorMsg, 'error');
                } else {
                    this.showMessage(data.error || 'Registration failed', 'error');
                }
            }
        } catch (error) {
            console.error('Registration error:', error);
            this.showMessage('Network error. Please try again.', 'error');
        } finally {
            this.hideLoading();
        }
    }

    // Handle email verification
    async handleVerification(e) {
        e.preventDefault();
        this.showLoading();

        const formData = new FormData(e.target);
        const code = formData.get('code');

        try {
            const response = await fetch(`${this.baseURL}/auth/verify`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    access_token: this.tokens.access,
                    email_code: code
                })
            });

            const data = await response.json();

            if (response.ok) {
                if (data.status === 'verified') {
                    this.showMessage('Email verified successfully!', 'success');
                    await this.loadUserInfo(); // Refresh user data
                    this.showDashboard();
                } else {
                    this.showMessage(data.message || 'Verification failed', 'error');
                }
            } else {
                this.showMessage(data.error || 'Verification failed', 'error');
            }
        } catch (error) {
            console.error('Verification error:', error);
            this.showMessage('Network error. Please try again.', 'error');
        } finally {
            this.hideLoading();
        }
    }

    // Handle forgot password
    async handleForgotPassword(e) {
        e.preventDefault();
        this.showLoading();

        const formData = new FormData(e.target);
        const email = formData.get('email');

        try {
            const response = await fetch(`${this.baseURL}/auth/forgot-password`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email: email })
            });

            const data = await response.json();

            if (response.ok) {
                this.showMessage('If an account with this email exists, you will receive reset instructions.', 'info');
                this.showForm('loginForm');
            } else {
                this.showMessage(data.error || 'Request failed', 'error');
            }
        } catch (error) {
            console.error('Forgot password error:', error);
            this.showMessage('Network error. Please try again.', 'error');
        } finally {
            this.hideLoading();
        }
    }

    // Handle change password
    async handleChangePassword(e) {
        e.preventDefault();

        if (!this.validateNewPasswordMatch()) {
            return;
        }

        this.showLoading();

        const formData = new FormData(e.target);
        const currentPassword = formData.get('currentPassword');
        const newPassword = formData.get('newPassword');

        try {
            const response = await fetch(`${this.baseURL}/auth/change-password`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    access_token: this.tokens.access,
                    old_password: currentPassword,
                    new_password: newPassword
                })
            });

            const data = await response.json();

            if (response.ok) {
                this.showMessage('Password changed successfully! Please log in again.', 'success');
                this.hideChangePasswordModal();
                this.handleLogout();
            } else {
                this.showMessage(data.error || 'Password change failed', 'error');
            }
        } catch (error) {
            console.error('Change password error:', error);
            this.showMessage('Network error. Please try again.', 'error');
        } finally {
            this.hideLoading();
        }
    }

    // Handle resend verification code
    async handleResendCode(e) {
        e.preventDefault();
        this.showLoading();

        try {
            const response = await fetch(`${this.baseURL}/auth/send-email`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    access_token: this.tokens.access
                })
            });

            const data = await response.json();

            if (response.ok) {
                this.showMessage('Verification code sent to your email!', 'success');
            } else {
                this.showMessage(data.error || 'Failed to send code', 'error');
            }
        } catch (error) {
            console.error('Resend code error:', error);
            this.showMessage('Network error. Please try again.', 'error');
        } finally {
            this.hideLoading();
        }
    }

    // Handle logout
    async handleLogout() {
        this.showLoading();

        try {
            // Log logout event with detailed information
            console.log('=== LOGOUT INITIATED ===');
            console.log('- Clearing all authentication tokens');
            console.log('- Access token will be invalidated');
            console.log('- Refresh token will be revoked on server');
            console.log('- Note: A new private key will be required for next login');

            // Logout from remote API - this revokes all tokens server-side
            const logoutResponse = await fetch(`${this.baseURL}/auth/logout`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    access_token: this.tokens.access
                })
            });

            if (logoutResponse.ok) {
                const logoutData = await logoutResponse.json();
                console.log(`- Server revoked ${logoutData.revoked_tokens || 0} tokens`);
            } else {
                console.warn('- Remote logout failed, continuing with local cleanup');
            }
        } catch (error) {
            console.error('Remote logout error:', error);
            console.log('- Continuing with local session cleanup');
        }

        try {
            // Clear local secure storage
            console.log('- Clearing encrypted local session storage');
            await this.clearLocalSession();
            console.log('- Local session cleared successfully');
        } catch (error) {
            console.error('Local session clear error:', error);
        }

        console.log('=== LOGOUT COMPLETE ===');
        console.log('You will need to login again');

        this.showMessage('Logged out successfully!', 'success');
        this.showForm('loginForm');
        this.hideLoading();
    }

    // Get private key from API
    async getPrivateKey() {
        try {
            // Return cached key if available
            if (this.cachedPrivateKey) {
                console.log('Using cached private key');
                return this.cachedPrivateKey;
            }

            const response = await fetch(`${this.baseURL}/auth/get-private-key`);
            if (response.ok) {
                const data = await response.json();
                this.cachedPrivateKey = data.private_key;

                // Set a timeout to clear the cached key after 7 days
                setTimeout(() => {
                    this.cachedPrivateKey = null;
                    console.log('Cached private key expired');
                }, 7 * 24 * 60 * 60 * 1000); // 7 days

                return data.private_key;
            }
            return null;
        } catch (error) {
            console.error('Get private key error:', error);
            return null;
        }
    }

    // Validate password match during registration
    validatePasswordMatch() {
        const password = document.getElementById('registerPassword');
        const confirmPassword = document.getElementById('confirmPassword');

        if (password.value !== confirmPassword.value) {
            confirmPassword.setCustomValidity('Passwords do not match');
            confirmPassword.classList.add('error');
            this.showMessage('Passwords do not match', 'error');
            return false;
        } else {
            confirmPassword.setCustomValidity('');
            confirmPassword.classList.remove('error');
            confirmPassword.classList.add('success');
            return true;
        }
    }

    // Validate new password match during change password
    validateNewPasswordMatch() {
        const newPassword = document.getElementById('newPassword');
        const confirmNewPassword = document.getElementById('confirmNewPassword');

        if (newPassword.value !== confirmNewPassword.value) {
            confirmNewPassword.setCustomValidity('Passwords do not match');
            confirmNewPassword.classList.add('error');
            this.showMessage('Passwords do not match', 'error');
            return false;
        } else {
            confirmNewPassword.setCustomValidity('');
            confirmNewPassword.classList.remove('error');
            confirmNewPassword.classList.add('success');
            return true;
        }
    }

    // Update user display information
    updateUserDisplay() {
        if (this.currentUser) {
            document.getElementById('userName').textContent = this.currentUser.username;
            document.getElementById('userEmail').textContent = this.currentUser.email;

            const statusElement = document.getElementById('verificationStatus');
            if (this.currentUser.email_verified) {
                statusElement.textContent = 'Verified';
                statusElement.className = 'status-verified';
            } else {
                statusElement.textContent = 'Unverified';
                statusElement.className = 'status-unverified';
            }
        }
    }

    // Test API endpoint
    async testAPI() {
        const endpoint = document.getElementById('endpoint').value || '/v1/health';
        const output = document.getElementById('out');

        output.textContent = `Requesting ${endpoint}...`;

        try {
            const url = endpoint.startsWith('/api/') ?
                `${this.localAPI}${endpoint}` :
                `${this.baseURL.replace('/v1', '')}${endpoint}`;

            const response = await fetch(url, {
                credentials: 'include'
            });

            const text = await response.text();
            const isJson = (response.headers.get('content-type') || '').includes('application/json');

            output.textContent = isJson ? JSON.stringify(JSON.parse(text), null, 2) : text;

            if (response.ok) {
                console.log('API Test OK');
            } else {
                console.warn(`API Test HTTP ${response.status}`);
            }
        } catch (error) {
            output.textContent = `Error: ${error.message}`;
        }
    }

    // Show specific form and hide others
    showForm(formId) {
        const forms = ['loginForm', 'registerForm', 'verificationForm', 'forgotPasswordForm'];
        forms.forEach(id => {
            document.getElementById(id).classList.add('hidden');
        });
        document.getElementById('dashboard').classList.add('hidden');
        document.getElementById(formId).classList.remove('hidden');

        // Clear form inputs
        document.querySelectorAll(`#${formId} input`).forEach(input => {
            input.value = '';
            input.classList.remove('error', 'success');
        });
    }

    // Show dashboard
    showDashboard() {
        const forms = ['loginForm', 'registerForm', 'verificationForm', 'forgotPasswordForm'];
        forms.forEach(id => {
            document.getElementById(id).classList.add('hidden');
        });
        document.getElementById('dashboard').classList.remove('hidden');
    }

    // Show/hide loading spinner
    showLoading() {
        document.getElementById('loading').classList.remove('hidden');
    }

    hideLoading() {
        document.getElementById('loading').classList.add('hidden');
    }

    // Show/hide messages
    showMessage(text, type = 'info') {
        const messageEl = document.getElementById('message');
        const textEl = document.getElementById('messageText');

        textEl.textContent = text;
        messageEl.className = `message ${type}`;
        messageEl.classList.remove('hidden');

        // Auto-hide after 5 seconds
        setTimeout(() => {
            this.hideMessage();
        }, 5000);
    }

    hideMessage() {
        document.getElementById('message').classList.add('hidden');
    }

    // Show/hide change password modal
    showChangePasswordModal() {
        document.getElementById('changePasswordModal').classList.remove('hidden');
        // Clear form
        document.querySelectorAll('#changePassword input').forEach(input => {
            input.value = '';
            input.classList.remove('error', 'success');
        });
    }

    hideChangePasswordModal() {
        document.getElementById('changePasswordModal').classList.add('hidden');
    }
}

// Initialize the authentication system when DOM is loaded
document.addEventListener('DOMContentLoaded', () => {
    new TungstenAuth();
});
