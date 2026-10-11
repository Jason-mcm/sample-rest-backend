CREATE TABLE IF NOT EXISTS app_user (
	id UUID PRIMARY KEY,
	name VARCHAR(100) NOT NULL
);

CREATE TABLE team (
	id UUID PRIMARY KEY,
	name VARCHAR(100) NOT NULL
);

CREATE TABLE membership (
  user_id UUID NOT NULL REFERENCES app_user(id),
  team_id UUID NOT NULL REFERENCES team(id),
  PRIMARY KEY (user_id, team_id)
);

CREATE TABLE note (
  id UUID PRIMARY KEY,
  team_id UUID NOT NULL REFERENCES team(id),
  title TEXT NOT NULL CHECK (length(trim(title)) > 0),
  content TEXT NOT NULL CHECK (length(content) <= 50000),
  created_by UUID NOT NULL REFERENCES app_user(id),
  updated_by UUID NOT NULL REFERENCES app_user(id),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX note_team_order ON note (team_id, updated_at DESC, id DESC);
