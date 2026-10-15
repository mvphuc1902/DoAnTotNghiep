# Human Resources

Open **Human Resources → Leave Requests / Tasks / Payroll** in the sidebar.
The application creates three new PostgreSQL tables on startup, without replacing existing data:
`hr_leave_requests`, `hr_tasks`, `hr_payroll`. They use dedicated `/api/hr` endpoints and are intentionally not exposed through unrestricted generic ERP CRUD.

## Permissions

- All signed-in accounts can request leave, cancel their own pending request, update assigned tasks and view their own salary worksheets.
- Admin / Director can view all HR records, assign tasks, review another person's leave, and create/edit/delete payroll. Dashboard-only permission does not grant HR management.
- Manager assigns tasks to Leaders in the same department. Leader assigns tasks to Employees in the same department and team. Admin / Director can assign across teams. Only Admin / Director can delete tasks. An assignee updates work notes, progress (0–100%), deadline and status; assignment title, brief, priority and recipient are controlled by the assigning role. Only that role can cancel/reopen cancelled work.
- Pending and approved leave ranges cannot overlap for the same account. A reviewed request cannot be reviewed again; a user cannot approve their own request. Only rejected/cancelled requests can be deleted by Admin / Director.
- HR uses user accounts as identity, including accounts without an employee row. Link employee profiles to users in Employees when applicable.

## Salary worksheets (VND)

- Monthly: base salary × verified paid days / standard working days (default 26; editable).
- Hourly: hourly rate × verified worked hours.
- Per task: rate × verified completed task units.
- Gross = base pay + overtime hours × overtime rate + allowance + bonus.
- Net = gross − entered deductions.

Server calculations use BigDecimal with HALF_UP rounding to 2 decimal places. One worksheet per account/month is permitted. The UI previews the server calculation; save recalculates it.
Paid days, completed units, overtime rate, insurance and tax deductions are entered explicitly. There is no automatic attendance, leave balance, statutory tax/insurance calculation, payment execution or accounting posting. No live salary figures are fabricated or seeded.

Lists show the latest 500 accessible records with local search, filters and 20-row paging.

## Email salary statements

Admin / Director: Payroll → View → Email payslip → review recipient and statement → Send to employee.
The destination is the email field on the employee's **user account**, never an arbitrary address supplied by the browser. One message contains only that account's statement. The API checks permissions, locks the salary and recipient during sending, rejects stale previews, and prevents sending an unchanged version twice. Editing salary marks the current version unsent and retains last successful send information.

Set these environment variables in the Spring Boot run configuration (keep credentials out of Git and chat), then restart:

```
ERP_MAIL_HOST=<your SMTP server>
ERP_MAIL_PORT=587
ERP_MAIL_USERNAME=<SMTP username>
ERP_MAIL_PASSWORD=<SMTP credential>
ERP_MAIL_FROM=<authorized sender address>
ERP_MAIL_SSL=false
```

STARTTLS is required by default. Set `ERP_MAIL_SSL=true` and the provider's implicit TLS port if required. Authentication, certificate verification and connection/read/write timeouts are enabled. OAuth-only SMTP providers require additional integration; username/password configuration will not bypass their policy.

Without configuration, previews work but sending is disabled. SENT means the SMTP server accepted the message, not confirmed inbox delivery. No automatic retry is performed after a timeout: check mail logs before manually retrying, since an ambiguous SMTP failure or a database failure after sending can otherwise produce a duplicate. Messages use multipart/alternative: a UTF-8 text fallback and an HTML payslip report with employee details, earnings, deductions and net pay. The sandboxed browser preview uses the identical server-rendered HTML. This is an RDLC-style report layout, not a .rdlc file or PDF attachment. No real email is sent by automated tests.

Task scope uses the role, department and team fields on Users, maintained by Admin. Blank departments/teams do not match each other. Employee profile fields and Dashboard access do not grant task assignment rights. Managers can view departmental tasks; Leaders can view their own tasks and employee tasks in their team. Staff see only assigned work. Done requires 100% progress and To do requires 0%.



Role HR: may view all salary worksheets, select employee accounts, create/update payroll, preview and send payslip emails. HR has no automatic Dashboard, user-management, task-management or payroll-delete privileges. Admin assigns Human Resources in Users -> Edit -> Role. SMTP configuration is still required for sending.


HR workspace: HR sees Human Resources only and lands on Payroll; all non-HR business APIs are denied, even with a Dashboard grant. Recruitment stores posts in hr_job_posts. HR/Admin/Director manage Draft/Published/Closed posts; signed-in staff read current Published posts. This is internal ERP publishing, not an external job board. Close a published post before deleting it.


Sender defaults to maivinhphucceo2103@gmail.com via smtp.gmail.com, STARTTLS port 587. ERP_MAIL_* environment overrides remain supported; configure ERP_MAIL_PASSWORD locally before sending. HR can view employee profiles through /api/hr/employees without accessing other ERP APIs. News and announcements use hr_posts (created_by -> users.id), with Draft/Published/Archived and a read/preview screen. Only published posts are readable by ordinary staff; HR/Admin/Director manage them. Recruitment remains in hr_job_posts. No broadcast emails are sent automatically when publishing.


HR does not see Tasks. All /api/hr/tasks and /api/hr/task-people requests from HR are denied. Employees, recruitment, news/announcements, payroll and leave requests remain available in the HR workspace.


### Gmail setup in IntelliJ

1. In the Google account used by ERP_MAIL_USERNAME, enable 2-Step Verification and create an App Password if the account allows it. Use the App Password for this SMTP integration.
2. Open Run -> Edit Configurations -> the Spring Boot application -> Environment variables. Set ERP_MAIL_PASSWORD locally to the App Password. Do not paste it in chat or commit it. Keep smtp.gmail.com, port 587, and ERP_MAIL_SSL=false for STARTTLS.
3. Stop and start the Spring Boot application so it receives the variable. Refresh the Payroll email preview. The Send button is enabled when configuration validation passes; this does not verify the SMTP credentials.
4. Review the employee's recipient address and payslip before sending. An authentication error means the SMTP server rejected the credentials. A general SMTP failure can have an uncertain delivery outcome; check delivery before retrying.

Google instructions: https://support.google.com/mail/answer/7104828?hl=en

### Local password setup without editing source code

From the project PowerShell terminal, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\configure-mail.ps1
```

The prompt hides the App Password. It saves the credential as plaintext in `config/mail-local.properties`, restricted to your Windows account and SYSTEM and excluded from Git. Spring imports this optional file when started with the project directory as its working directory. Restart Spring Boot after saving. Existing ERP_MAIL_* environment variables take priority; remove obsolete overrides from your run configuration. This script does not send mail. In Payroll, open the employee's statement, review its email preview, and use Send to employee yourself. The recipient comes from the user account linked to the payroll record.
