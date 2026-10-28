CREATE INDEX IF NOT EXISTS idx_chat_session_user_updated_at_id
    ON chat_session(user_id, updated_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_chat_message_session_created_at_id
    ON chat_message(session_id, created_at, id);

CREATE INDEX IF NOT EXISTS idx_chat_message_stale_assistant_placeholder
    ON chat_message(created_at, id)
    WHERE role = 'ASSISTANT' AND content = '';
