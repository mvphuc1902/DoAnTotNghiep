const originalFetch = window.fetch.bind(window);
let currentUser = null;
let sessionCsrf = null;
const accountRoleLabels = {HR: 'Human Resources', ADMIN: 'Administrator', DIRECTOR: 'Director', MANAGER: 'Manager', LEADER: 'Team Leader', EMPLOYEE: 'Employee', USER: 'Employee'};
function isAdmin() { return currentUser?.role === 'ADMIN'; }
function canViewDashboard() { return currentUser?.role !== 'HR' && (['ADMIN', 'DIRECTOR'].includes(currentUser?.role) || currentUser?.dashboard_access === true); }
function canDeleteRecords() { return ['ADMIN', 'DIRECTOR'].includes(currentUser?.role); }
const authReady = (async () => {
  if (location.protocol === 'file:' || (['localhost', '127.0.0.1'].includes(location.hostname) && location.port !== '8081')) {
    location.replace('http://localhost:8081/login.html');
    throw new Error('Open ERP from the Spring Boot server.');
  }
  const csrfResponse = await originalFetch('/api/auth/csrf', {cache: 'no-store'});
  if (!csrfResponse.ok) throw new Error('Cannot initialize session.');
  sessionCsrf = await csrfResponse.json();
  const response = await originalFetch('/api/auth/me', {cache: 'no-store'});
  if (response.status === 401) { location.replace('/login.html'); throw new Error('Login required.'); }
  if (!response.ok) throw new Error('Cannot load account.');
  currentUser = await response.json();
  if (document.readyState === 'loading') await new Promise(resolve => document.addEventListener('DOMContentLoaded', resolve, {once: true}));
  document.documentElement.dataset.authenticated = 'true';
  document.documentElement.dataset.role = currentUser.role;
  document.documentElement.dataset.dashboard = String(canViewDashboard());
  document.documentElement.dataset.delete = String(canDeleteRecords());
  document.getElementById('session-name').textContent = currentUser.name || currentUser.username;
  const department = document.getElementById('session-department');
  department.textContent = currentUser.department || 'Chưa cập nhật phòng ban';
  department.title = department.textContent;
  return currentUser;
})();
window.fetch = async (input, options = {}) => {
  const url = new URL(input instanceof Request ? input.url : input, location.href);
  if (url.origin !== location.origin || !url.pathname.startsWith('/api/')) return originalFetch(input, options);
  await authReady;
  const method = (options.method || (input instanceof Request ? input.method : 'GET')).toUpperCase();
  const headers = new Headers(options.headers || (input instanceof Request ? input.headers : undefined));
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set(sessionCsrf.headerName, sessionCsrf.token);
  const response = await originalFetch(input, {...options, headers});
  if (response.status === 401) { location.replace('/login.html?expired=1'); throw new Error('Session expired. Please sign in again.'); }
  return response;
};
async function logout() {
  const button = document.getElementById('logout-button'); button.disabled = true;
  try {
    const response = await fetch('/api/auth/logout', {method: 'POST'});
    if (!response.ok) throw new Error('Could not sign out. Please try again.');
    location.replace('/login.html?logout=1');
  } catch (error) { button.disabled = false; alert(error.message); }
}
authReady.then(() => { if (currentUser.role==='HR') { renderHrOnlyNavigation(); openHr('payroll'); } else if (canViewDashboard()) switchTab('dashboard'); }).catch(error => {
  if (document.body && !currentUser) {
    document.documentElement.dataset.authenticated = 'error';
    const message = document.createElement('p'); message.textContent = 'Không kết nối được phiên đăng nhập. Tải lại trang hoặc mở http://localhost:8081/login.html.';
    document.body.replaceChildren(message);
  }
});
window.addEventListener('pageshow', event => { if (event.persisted) location.reload(); });


