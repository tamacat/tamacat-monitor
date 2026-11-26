-- V002: force a password change (initial administrator, password reset by an administrator)
alter table users add column must_change_password boolean not null default false;
