-- V004: passkeys (WebAuthn, T-801)
-- webauthn_user_id: the user handle given to authenticators (random, not the numeric user id)
alter table users add column webauthn_user_id varbinary(64) null;

-- credential_id: as returned by the authenticator (unique across all users)
-- public_key: COSE key; algorithm: COSE algorithm (-7 ES256, -8 EdDSA, -257 RS256)
-- sign_count: last signature counter (0 for synced passkeys)
create table user_passkeys (
  passkey_id      bigint          not null auto_increment primary key,
  user_id         bigint          not null,
  credential_id   varbinary(1023) not null,
  public_key      varbinary(2048) not null,
  algorithm       int             not null,
  sign_count      bigint          not null default 0,
  name            varchar(100)    not null,
  transports      varchar(200)    null,
  backup_eligible boolean         not null default false,
  backed_up       boolean         not null default false,
  created_at      datetime(3)     not null,
  last_used_at    datetime(3)     null,
  unique key uk_user_passkeys_credential (credential_id),
  index ix_user_passkeys_user (user_id),
  constraint fk_user_passkeys_user foreign key (user_id) references users (user_id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;
