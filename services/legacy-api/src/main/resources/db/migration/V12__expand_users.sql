ALTER TABLE users ADD COLUMN (
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    display_name VARCHAR(100),
    nickname VARCHAR(100),
    website_url VARCHAR(500),
    bio TEXT,
    locale VARCHAR(10) DEFAULT 'ja_JP',
    avatar_url VARCHAR(500),
    department VARCHAR(100),
    position VARCHAR(100)
);
