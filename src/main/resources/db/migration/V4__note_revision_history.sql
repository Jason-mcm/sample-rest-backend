ALTER TABLE note ADD CONSTRAINT note_id_team_unique UNIQUE (id, team_id);

CREATE TABLE note_revision (
  id UUID PRIMARY KEY,
  note_id UUID NOT NULL,
  team_id UUID NOT NULL,
  version BIGINT NOT NULL CHECK (version >= 0),
  title TEXT NOT NULL CHECK (length(trim(title)) > 0),
  content TEXT NOT NULL CHECK (length(content) <= 50000),
  edited_by UUID NOT NULL REFERENCES app_user(id),
  edited_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT revision_note_team_fk FOREIGN KEY (note_id, team_id)
    REFERENCES note(id, team_id) ON DELETE CASCADE,
  CONSTRAINT revision_note_version_unique UNIQUE (note_id, version)
);

-- Preserve the current state of existing notes. Earlier versions are unavailable.
-- Reusing the note UUID is safe: revision IDs have their own table namespace.
INSERT INTO note_revision (id, note_id, team_id, version, title, content, edited_by, edited_at)
SELECT id, id, team_id, version, title, content, updated_by, updated_at FROM note;
