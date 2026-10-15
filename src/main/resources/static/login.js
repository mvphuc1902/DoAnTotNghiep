(() => {
  if (location.protocol === 'file:' || (['localhost', '127.0.0.1', '[::1]'].includes(location.hostname) && location.port !== '8081')) {
    location.replace('http://localhost:8081/login.html');
    return;
  }
const loginNotice = document.getElementById('login-notice');
const query = new URLSearchParams(location.search);
if (query.has('logout')) loginNotice.textContent = 'You have signed out.';
if (query.has('expired')) loginNotice.textContent = 'Your session has expired. Please sign in again.';
fetch('/api/auth/me', {cache: 'no-store'}).then(response => { if (response.ok) location.replace('/'); }).catch(() => {});
document.getElementById('login-form').addEventListener('submit', async event => {
  event.preventDefault();
  const button = document.getElementById('login-submit'); if (button.disabled) return;
  const error = document.getElementById('login-error'); error.textContent = '';
  button.disabled = true; button.textContent = 'Signing in…';
  try {
    const tokenResponse = await fetch('/api/auth/csrf', {cache: 'no-store'});
    if (!tokenResponse.ok) throw new Error('Could not start a session. Please try again.');
    const csrf = await tokenResponse.json();
    const response = await fetch('/api/auth/login', {method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded', [csrf.headerName]: csrf.token},
      body: new URLSearchParams({username: event.target.elements.username.value.trim(), password: event.target.elements.password.value})});
    if (!response.ok) throw new Error(response.status === 401 ? 'Invalid username or password.' : 'Could not sign in. Please try again.');
    location.replace('/');
  } catch (failure) { error.textContent = failure.message || 'Unable to connect to the server.'; }
  finally { button.disabled = false; button.textContent = 'Sign in'; }
});

})();
