CREATE TABLE registrations (
    idempotency_key uuid PRIMARY KEY,
    identity_name uuid NOT NULL UNIQUE,
    email varchar(254) NOT NULL UNIQUE,
    first_name varchar(80) NOT NULL,
    last_name varchar(80) NOT NULL,
    subject varchar(255) UNIQUE,
    finished_at timestamptz
);

CREATE TABLE profiles (
    subject varchar(255) PRIMARY KEY,
    display_name varchar(161) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
