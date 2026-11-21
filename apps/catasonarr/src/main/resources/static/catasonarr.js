// Catasonarr's page (orch.md decision 109): shows who is signed in and signs out through Dex.

/** The cookie Spring Security writes the CSRF token into (SEC-2). */
const CSRF_COOKIE = 'XSRF-TOKEN';

/**
 * Reads the CSRF token from its cookie.
 *
 * @returns {string} the token, or an empty string when there is none
 */
function csrfToken() {
  const cookie = document.cookie.split('; ').find((c) => c.startsWith(`${CSRF_COOKIE}=`));
  return cookie ? decodeURIComponent(cookie.slice(CSRF_COOKIE.length + 1)) : '';
}

/** Posts the logout form, which ends Catasonarr's session and Dex's. */
function signOut() {
  const form = document.createElement('form');
  form.method = 'post';
  form.action = '/logout';
  const token = document.createElement('input');
  token.type = 'hidden';
  token.name = '_csrf';
  token.value = csrfToken();
  form.append(token);
  document.body.append(form);
  form.submit();
}

/** Shows the signed-in user and Sign out, when Catasonarr runs with login. */
async function showUser() {
  const response = await fetch('/api/v1/session');
  if (response.status === 401) {
    window.location.reload(); // the session expired: the reload goes through Dex
    return;
  }
  const session = await response.json();
  if (!session.login) {
    return;
  }
  const user = document.querySelector('[data-testid="user"]');
  user.querySelector('[data-testid="user-name"]').textContent = session.user;
  user.querySelector('[data-testid="sign-out"]').addEventListener('click', signOut);
  user.hidden = false;
}

showUser();
