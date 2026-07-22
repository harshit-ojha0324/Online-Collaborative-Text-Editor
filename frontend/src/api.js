// Single source of truth for backend URLs and authenticated requests. Deployment overrides the two
// VITE_* variables (see .env.example); local dev falls back to the Spring Boot defaults.

export const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:3000';
export const WS_URL = import.meta.env.VITE_WS_URL ?? 'ws://localhost:3000/docs/ws';

// The token is stored already carrying its "Bearer " scheme, so it is used verbatim as the header.
export function getToken() {
    return localStorage.getItem('jwtKey');
}

export function clearSession() {
    localStorage.removeItem('username');
    localStorage.removeItem('jwtKey');
}

/**
 * Authenticated fetch against the API. Attaches the bearer token, defaults JSON content-type for
 * bodies, and centralises session expiry: a 401/403 clears the stored token and bounces to the login
 * screen instead of leaving the UI in a broken half-authenticated state.
 */
export async function apiFetch(path, options = {}) {
    const headers = {...(options.headers || {})};
    const token = getToken();
    if (token) headers['Authorization'] = token;
    if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';

    const response = await fetch(`${API_URL}${path}`, {...options, headers});
    if (response.status === 401) {
        // Not authenticated (missing/expired token): clear it and return to the login screen.
        clearSession();
        if (window.location.pathname !== '/') window.location.assign('/');
        throw new Error('Session expired');
    }
    if (response.status === 403) {
        // Authenticated but not allowed on this document — surface as an error, do NOT log out.
        throw new Error('You do not have access to this resource');
    }
    return response;
}
