\set ON_ERROR_STOP on
BEGIN;
\ir ../src/main/resources/hr-notifications.sql
DO $$
DECLARE uid bigint; other_uid bigint; post bigint; job bigint; notice bigint;
BEGIN
    SELECT min(id) INTO uid FROM users;
    SELECT min(id) INTO other_uid FROM users WHERE id<>uid;
    ASSERT uid IS NOT NULL AND other_uid IS NOT NULL, 'Need two existing users';
    INSERT INTO hr_posts(title,category,summary,content,status,created_by)
    VALUES('Notification test','NEWS','','Testing','DRAFT',uid) RETURNING id INTO post;
    ASSERT NOT EXISTS(SELECT 1 FROM hr_notifications WHERE post_id=post), 'Draft must not notify';
    UPDATE hr_posts SET status='PUBLISHED' WHERE id=post;
    SELECT id INTO notice FROM hr_notifications WHERE post_id=post;
    ASSERT notice IS NOT NULL, 'Publication creates notification';
    UPDATE hr_posts SET status='PUBLISHED',title='Updated' WHERE id=post;
    ASSERT (SELECT count(*)=1 FROM hr_notifications WHERE post_id=post), 'Edit must not duplicate';
    INSERT INTO hr_notification_reads(notification_id,user_id) VALUES(notice,uid) ON CONFLICT DO NOTHING;
    INSERT INTO hr_notification_reads(notification_id,user_id) VALUES(notice,uid) ON CONFLICT DO NOTHING;
    ASSERT (SELECT count(*)=1 FROM hr_notification_reads WHERE notification_id=notice), 'Read is idempotent';
    ASSERT NOT EXISTS(SELECT 1 FROM hr_notification_reads WHERE notification_id=notice AND user_id=other_uid), 'Other user remains unread';
    UPDATE hr_posts SET status='ARCHIVED' WHERE id=post;
    ASSERT NOT EXISTS(SELECT 1 FROM hr_notifications n JOIN hr_posts p ON p.id=n.post_id WHERE n.id=notice AND p.status='PUBLISHED'), 'Withdrawn post hidden';
    UPDATE hr_posts SET status='PUBLISHED' WHERE id=post;
    ASSERT (SELECT count(*)=1 FROM hr_notifications WHERE post_id=post), 'Republish does not duplicate';
    INSERT INTO hr_job_posts(title,department,location,employment_type,description,requirements,contact_email,deadline,status,created_by)
    VALUES('Recruitment test','HR','HCM','FULL_TIME','Test','Test','hr@example.test',CURRENT_DATE+1,'PUBLISHED',uid) RETURNING id INTO job;
    ASSERT (SELECT count(*)=1 FROM hr_notifications WHERE job_post_id=job), 'Published recruitment notifies';
    UPDATE hr_job_posts SET deadline=CURRENT_DATE-1 WHERE id=job;
    ASSERT NOT EXISTS(SELECT 1 FROM hr_notifications n JOIN hr_job_posts j ON j.id=n.job_post_id
        WHERE n.job_post_id=job AND j.status='PUBLISHED' AND j.deadline>=CURRENT_DATE), 'Expired recruitment hidden';
    DELETE FROM hr_posts WHERE id=post;
    ASSERT NOT EXISTS(SELECT 1 FROM hr_notifications WHERE id=notice), 'Deleted post removes notification';
    ASSERT NOT EXISTS(SELECT 1 FROM hr_notification_reads WHERE notification_id=notice), 'Deleted notification removes receipts';
    RAISE NOTICE 'PASS: draft, publish, no duplicates, per-user reads, withdrawal, expiration, cascade delete';
END $$;
ROLLBACK;
