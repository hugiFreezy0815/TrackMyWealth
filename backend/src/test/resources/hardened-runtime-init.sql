-- The Testcontainers bootstrap user owns migrations; the application connects only as this role.
CREATE ROLE trackmywealth_runtime LOGIN PASSWORD 'runtime-integration-password'
    NOSUPERUSER NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO trackmywealth_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE trackmywealth IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO trackmywealth_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE trackmywealth IN SCHEMA public
    GRANT USAGE ON SEQUENCES TO trackmywealth_runtime;
