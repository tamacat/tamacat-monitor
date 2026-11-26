-- V003: two-factor authentication with TOTP (T-602)
-- mfa_secret: the TOTP secret encrypted with SecretCipher (AES-256-GCM, purpose "mfa:<user_id>")
-- mfa_last_step: last accepted time step (a code is accepted once)
alter table users add column mfa_enabled boolean not null default false;
alter table users add column mfa_secret varbinary(255) null;
alter table users add column mfa_last_step bigint not null default 0;

-- one-time recovery codes; only their SHA-256 is stored
create table user_recovery_codes (
  code_id    bigint       not null auto_increment primary key,
  user_id    bigint       not null,
  code_hash  char(64)     not null,
  used_at    datetime(3)  null,
  index ix_user_recovery_codes_user (user_id),
  constraint fk_user_recovery_codes_user foreign key (user_id) references users (user_id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;
