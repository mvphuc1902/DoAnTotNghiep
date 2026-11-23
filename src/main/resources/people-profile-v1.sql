CREATE TABLE user_avatars(user_id bigint PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,image bytea NOT NULL,updated_at timestamp NOT NULL DEFAULT now());
CREATE TABLE user_presence(user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,session_key varchar(64) NOT NULL,tab_id uuid NOT NULL,state varchar(10) NOT NULL CHECK(state IN ('ACTIVE','AWAY')),last_seen timestamp NOT NULL DEFAULT now(),PRIMARY KEY(user_id,session_key,tab_id));
CREATE INDEX user_presence_seen_idx ON user_presence(last_seen);
