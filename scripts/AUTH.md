# Sign in and permissions

Open `http://localhost:8081/login.html`. Serve the UI from Spring Boot so the browser can use the session cookie.

| Action | Employee / Leader / Manager | Director | Admin |
| --- | --- | --- | --- |
| View/create/edit business data | Yes | Yes | Yes |
| Delete business data | No | Yes | Yes |
| Manage accounts, roles and grants | No | No | Yes |
| Dashboard | Only if granted by Admin | Yes | Yes |

Roles are `EMPLOYEE`, `LEADER`, `MANAGER`, `DIRECTOR`, `ADMIN`. Admin assigns roles and the per-account `dashboard_access` grant in the Users form. This grant only unlocks Dashboard; it never grants user management or deletion. Legacy `USER` roles migrate to `EMPLOYEE`. Refresh the page after permission changes to update navigation; backend permission checks apply on the next request.
Administrators manage accounts in **Employees & Users → Users**. Username is unique (case insensitive); email is not used as a login identifier. Existing accounts receive usernames `user<ID>` and keep their passwords, migrated to BCrypt if needed. Accounts without a password need an administrator to set one. Passwords are never returned by the ERP APIs.

The first startup adds `username` and `role` to `users`. If no admin exists, set `ERP_ADMIN_PASSWORD` to a strong password of 12–72 bytes; the application creates `admin`. Subsequent restarts preserve that account and do not require this variable. The initial password for this workspace was generated separately and is not committed. A database backup before migration is in `target/erp-before-auth.dump`.

The application uses server sessions, an HttpOnly/SameSite cookie, a 30-minute inactivity timeout, and CSRF tokens for mutations (including login/logout). Logout invalidates the session. Account role changes apply on the next request; password changes invalidate old sessions. An administrator cannot delete/demote the currently logged-in admin or remove the final admin account. Business constraints (foreign keys and locked sales orders) still apply to admin actions.
