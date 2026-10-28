TRUNCATE TABLE admin_audit_log, database_backup,
    admin_job_item, admin_job, ingest_run_item,
    chat_message, chat_session, embedding_chunk, ugc_like, ugc_report, ugc_post,
    ingest_run, document, source,
    user_profile, user_memory RESTART IDENTITY CASCADE;

DELETE FROM model_configuration
WHERE configuration_key NOT IN ('bootstrap-chat', 'bootstrap-embedding');

UPDATE model_configuration
SET provider = NULL,
    model = NULL,
    base_url = NULL,
    temperature = NULL,
    dimension = CASE model_kind WHEN 'EMBEDDING' THEN 1536 ELSE NULL END,
    api_key_ciphertext = NULL,
    api_key_iv = NULL,
    encryption_key_id = NULL,
    status = 'ACTIVE',
    created_by = NULL,
    activated_at = now(),
    failure_reason = NULL,
    updated_at = now()
WHERE configuration_key IN ('bootstrap-chat', 'bootstrap-embedding');

DELETE FROM users;
ALTER SEQUENCE users_id_seq RESTART WITH 1;
