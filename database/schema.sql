-- Users table
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    avatar_url VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE,
    role VARCHAR(20) DEFAULT 'USER'
);

-- Notebooks table (Knowledge Bases)
CREATE TABLE notebooks (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    name VARCHAR(100) NOT NULL,
    description TEXT,
    cover_url VARCHAR(255),
    is_public BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Notes table
CREATE TABLE notes (
    id BIGSERIAL PRIMARY KEY,
    notebook_id BIGINT NOT NULL REFERENCES notebooks(id),
    title VARCHAR(200) NOT NULL DEFAULT 'Untitled',
    content TEXT, -- Markdown content
    content_html TEXT, -- Rendered HTML for faster display/search
    summary TEXT, -- AI generated summary
    cover_image VARCHAR(255),
    status VARCHAR(20) DEFAULT 'DRAFT', -- DRAFT, PUBLISHED, TRASH
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    version INT DEFAULT 1
);

-- Tags table
CREATE TABLE tags (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    name VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, name)
);

-- Note Tags association
CREATE TABLE note_tags (
    note_id BIGINT REFERENCES notes(id) ON DELETE CASCADE,
    tag_id BIGINT REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (note_id, tag_id)
);

-- Public shares
CREATE TABLE note_shares (
    id BIGSERIAL PRIMARY KEY,
    note_id BIGINT NOT NULL REFERENCES notes(id),
    token VARCHAR(64) NOT NULL UNIQUE,
    expire_at TIMESTAMP WITH TIME ZONE,
    is_active BOOLEAN DEFAULT TRUE,
    extraction_code VARCHAR(20),
    allow_comment BOOLEAN DEFAULT FALSE,
    allow_edit BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Share comments
CREATE TABLE note_comments (
    id BIGSERIAL PRIMARY KEY,
    share_id BIGINT NOT NULL REFERENCES note_shares(id) ON DELETE CASCADE,
    content VARCHAR(1000) NOT NULL,
    author_name VARCHAR(50) NOT NULL,
    anchor_key VARCHAR(120),
    anchor_type VARCHAR(20),
    anchor_label VARCHAR(120),
    anchor_preview VARCHAR(300),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for performance
CREATE INDEX idx_notes_user_notebook ON notes(notebook_id);
CREATE INDEX idx_notes_title ON notes(title);
CREATE INDEX idx_tags_user ON tags(user_id);
CREATE INDEX idx_note_shares_note ON note_shares(note_id);
CREATE INDEX idx_note_comments_share ON note_comments(share_id);
