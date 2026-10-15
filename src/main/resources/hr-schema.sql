ALTER TABLE users ADD COLUMN IF NOT EXISTS team varchar(255);
CREATE TABLE IF NOT EXISTS hr_posts (
 id bigserial PRIMARY KEY,
 title varchar(200) NOT NULL,
 category varchar(20) NOT NULL CHECK (category IN ('NEWS','ANNOUNCEMENT')),
 summary varchar(500) NOT NULL DEFAULT '',
 content text NOT NULL CHECK (length(content) BETWEEN 1 AND 20000),
 status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
 created_by bigint NOT NULL REFERENCES users(id),
 created_at timestamp NOT NULL DEFAULT current_timestamp,
 updated_at timestamp NOT NULL DEFAULT current_timestamp,
 published_at timestamp
);
CREATE INDEX IF NOT EXISTS hr_posts_status_idx ON hr_posts(status, published_at DESC);
CREATE TABLE IF NOT EXISTS hr_post_images (
 post_id bigint PRIMARY KEY REFERENCES hr_posts(id) ON DELETE CASCADE,
 content_type varchar(20) NOT NULL CHECK (content_type IN ('image/jpeg','image/png')),
 image_data bytea NOT NULL CHECK (octet_length(image_data) BETWEEN 1 AND 5242880)
);
CREATE TABLE IF NOT EXISTS hr_job_posts (
 id bigserial PRIMARY KEY,
 title varchar(200) NOT NULL,
 department varchar(255) NOT NULL,
 location varchar(255) NOT NULL,
 employment_type varchar(20) NOT NULL CHECK (employment_type IN ('FULL_TIME','PART_TIME','CONTRACT','INTERNSHIP')),
 description varchar(8000) NOT NULL,
 requirements varchar(8000) NOT NULL,
 contact_email varchar(255) NOT NULL,
 deadline date NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','CLOSED')),
 created_by bigint NOT NULL REFERENCES users(id),
 updated_at timestamp NOT NULL DEFAULT current_timestamp
);
CREATE TABLE IF NOT EXISTS hr_job_post_images (
 post_id bigint PRIMARY KEY REFERENCES hr_job_posts(id) ON DELETE CASCADE,
 content_type varchar(20) NOT NULL CHECK (content_type IN ('image/jpeg','image/png')),
 image_data bytea NOT NULL CHECK (octet_length(image_data) BETWEEN 1 AND 5242880)
);
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
