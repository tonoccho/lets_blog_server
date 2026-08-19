CREATE TABLE ssh_key_pairs
(
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                      VARCHAR(100)  NOT NULL,
    comment                   VARCHAR(255)  NULL,
    public_key_line           TEXT          NOT NULL,
    private_key_encrypted     VARBINARY(4096) NOT NULL,
    created_at                DATETIME      NOT NULL,
    CONSTRAINT uk_ssh_key_pairs_name UNIQUE (name)
);
