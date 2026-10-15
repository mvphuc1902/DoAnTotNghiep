-- Run in pgAdmin Query Tool connected to the existing ERP database.
-- Requires public.users and public.employees from the existing application.
-- The last three tables are optional groundwork; application integration is not implemented yet.
BEGIN;
SET LOCAL search_path TO public;
ALTER TABLE users ADD COLUMN IF NOT EXISTS department varchar(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS team varchar(255);
CREATE TABLE IF NOT EXISTS hr_leave_requests (
 id bigserial PRIMARY KEY,
 user_id bigint NOT NULL REFERENCES users(id),
 start_date date NOT NULL,
 end_date date NOT NULL CHECK (end_date >= start_date),
 leave_type varchar(20) NOT NULL CHECK (leave_type IN ('ANNUAL','UNPAID','SICK','OTHER')),
 reason varchar(2000) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
 review_note varchar(2000),
 reviewed_by bigint REFERENCES users(id),
 reviewed_at timestamp,
 created_at timestamp NOT NULL DEFAULT current_timestamp
);
CREATE INDEX IF NOT EXISTS hr_leave_user_idx ON hr_leave_requests(user_id);
CREATE TABLE IF NOT EXISTS hr_tasks (
 id bigserial PRIMARY KEY,
 title varchar(200) NOT NULL,
 description varchar(4000) NOT NULL DEFAULT '',
 assigned_to bigint NOT NULL REFERENCES users(id),
 created_by bigint NOT NULL REFERENCES users(id),
 due_date date NOT NULL,
 priority varchar(10) NOT NULL CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
 status varchar(20) NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO','IN_PROGRESS','DONE','CANCELLED')),
 created_at timestamp NOT NULL DEFAULT current_timestamp,
 updated_at timestamp NOT NULL DEFAULT current_timestamp
);
CREATE INDEX IF NOT EXISTS hr_tasks_assignee_idx ON hr_tasks(assigned_to);
ALTER TABLE hr_tasks ADD COLUMN IF NOT EXISTS progress integer NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100);
ALTER TABLE hr_tasks ADD COLUMN IF NOT EXISTS work_note varchar(4000) NOT NULL DEFAULT '';
UPDATE hr_tasks SET progress=100 WHERE status='DONE' AND progress<>100;
CREATE TABLE IF NOT EXISTS hr_payroll (
 id bigserial PRIMARY KEY,
 user_id bigint NOT NULL REFERENCES users(id),
 pay_month date NOT NULL CHECK (extract(day FROM pay_month) = 1),
 method varchar(10) NOT NULL CHECK (method IN ('MONTHLY','HOURLY','TASK')),
 base_rate numeric(18,2) NOT NULL CHECK (base_rate >= 0),
 standard_days numeric(8,2) NOT NULL CHECK (standard_days > 0),
 units numeric(10,2) NOT NULL CHECK (units >= 0),
 overtime_hours numeric(10,2) NOT NULL CHECK (overtime_hours >= 0),
 overtime_rate numeric(18,2) NOT NULL CHECK (overtime_rate >= 0),
 allowance numeric(18,2) NOT NULL CHECK (allowance >= 0),
 bonus numeric(18,2) NOT NULL CHECK (bonus >= 0),
 deductions numeric(18,2) NOT NULL CHECK (deductions >= 0),
 gross_pay numeric(18,2) NOT NULL,
 net_pay numeric(18,2) NOT NULL CHECK (net_pay >= 0),
 notes varchar(2000) NOT NULL DEFAULT '',
 updated_by bigint NOT NULL REFERENCES users(id),
 updated_at timestamp NOT NULL DEFAULT current_timestamp,
 UNIQUE(user_id, pay_month)
);
ALTER TABLE hr_payroll ADD COLUMN IF NOT EXISTS email_status varchar(16) NOT NULL DEFAULT 'NOT_SENT';
ALTER TABLE hr_payroll ADD COLUMN IF NOT EXISTS email_recipient varchar(255);
ALTER TABLE hr_payroll ADD COLUMN IF NOT EXISTS email_sent_at timestamp;
ALTER TABLE hr_payroll ADD COLUMN IF NOT EXISTS email_sent_version timestamp;


-- Additional HR tables: attendance, salary configuration and annual leave balances.
CREATE TABLE IF NOT EXISTS hr_attendance (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    work_date date NOT NULL,
    worked_hours numeric(5,2) NOT NULL DEFAULT 0 CHECK (worked_hours BETWEEN 0 AND 24),
    overtime_hours numeric(5,2) NOT NULL DEFAULT 0 CHECK (overtime_hours BETWEEN 0 AND 24),
    paid_day_fraction numeric(3,2) NOT NULL DEFAULT 0 CHECK (paid_day_fraction BETWEEN 0 AND 1),
    status varchar(16) NOT NULL DEFAULT 'PRESENT'
        CHECK (status IN ('PRESENT','ABSENT','PAID_LEAVE','UNPAID_LEAVE','HOLIDAY')),
    notes varchar(2000) NOT NULL DEFAULT '',
    updated_by bigint REFERENCES users(id) ON DELETE RESTRICT,
    updated_at timestamp NOT NULL DEFAULT current_timestamp,
    CHECK (worked_hours + overtime_hours <= 24),
    UNIQUE (user_id, work_date)
);

CREATE TABLE IF NOT EXISTS hr_salary_configs (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    effective_from date NOT NULL,
    method varchar(10) NOT NULL CHECK (method IN ('MONTHLY','HOURLY','TASK')),
    base_rate numeric(18,2) NOT NULL CHECK (base_rate >= 0),
    standard_days numeric(5,2) NOT NULL DEFAULT 26 CHECK (standard_days > 0 AND standard_days <= 31),
    overtime_rate numeric(18,2) NOT NULL DEFAULT 0 CHECK (overtime_rate >= 0),
    allowance numeric(18,2) NOT NULL DEFAULT 0 CHECK (allowance >= 0),
    currency_code varchar(3) NOT NULL DEFAULT 'VND' CHECK (currency_code = 'VND'),
    updated_by bigint REFERENCES users(id) ON DELETE RESTRICT,
    updated_at timestamp NOT NULL DEFAULT current_timestamp,
    UNIQUE (user_id, effective_from)
);
-- Pick the latest effective_from <= the relevant payroll date when integration is added.

CREATE TABLE IF NOT EXISTS hr_leave_balances (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    leave_year integer NOT NULL CHECK (leave_year BETWEEN 1900 AND 9999),
    entitled_days numeric(7,2) NOT NULL DEFAULT 0 CHECK (entitled_days >= 0),
    carried_days numeric(7,2) NOT NULL DEFAULT 0 CHECK (carried_days >= 0),
    used_days numeric(7,2) NOT NULL DEFAULT 0 CHECK (used_days >= 0),
    remaining_days numeric(7,2) GENERATED ALWAYS AS (entitled_days + carried_days - used_days) STORED,
    updated_by bigint REFERENCES users(id) ON DELETE RESTRICT,
    updated_at timestamp NOT NULL DEFAULT current_timestamp,
    CHECK (used_days <= entitled_days + carried_days),
    UNIQUE (user_id, leave_year)
);
-- Annual paid leave balance only. Application must define working days/holidays before automatic deduction.

-- Add the employee/account foreign key only when an equivalent FK is absent.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=c.conkey[1]
        JOIN pg_attribute b ON b.attrelid=c.confrelid AND b.attnum=c.confkey[1]
        WHERE c.contype='f' AND c.conrelid='public.employees'::regclass
          AND c.confrelid='public.users'::regclass AND array_length(c.conkey,1)=1
          AND a.attname='user_id' AND b.attname='id'
    ) THEN
        ALTER TABLE employees ADD CONSTRAINT fk_employees_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS hr_tasks_creator_idx ON hr_tasks(created_by);
CREATE INDEX IF NOT EXISTS hr_leave_reviewer_idx ON hr_leave_requests(reviewed_by);
COMMIT;

-- Inspect HR foreign keys after execution.
SELECT conrelid::regclass AS table_name, conname AS constraint_name,
       pg_get_constraintdef(oid) AS relationship
FROM pg_constraint
WHERE contype='f' AND connamespace='public'::regnamespace
  AND (conrelid::regclass::text LIKE 'hr_%' OR conrelid='public.employees'::regclass)
ORDER BY conrelid::regclass::text, conname;

