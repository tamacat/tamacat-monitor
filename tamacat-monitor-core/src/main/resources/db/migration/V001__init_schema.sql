-- V001: initial schema of the central DB (MySQL 8.0+)
-- Times are stored as UTC in datetime(3) (timestamp has the year-2038 limit).

create table users (
  user_id             bigint        not null auto_increment primary key,
  login_id            varchar(100)  not null,
  display_name        varchar(200)  not null,
  email               varchar(254)  null,
  -- PBKDF2 hash: "pbkdf2-sha256$<iterations>$<salt b64>$<hash b64>"
  password_hash       varchar(255)  not null,
  locale              varchar(10)   not null default 'en',
  enabled             boolean       not null default true,
  failed_count        int           not null default 0,
  locked_until        datetime(3)   null,
  password_changed_at datetime(3)   null,
  last_login_at       datetime(3)   null,
  created_at          datetime(3)   not null,
  updated_at          datetime(3)   not null,
  constraint uk_users_login_id unique (login_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

create table roles (
  role_code   varchar(30)  not null primary key,
  description varchar(200) not null
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

insert into roles (role_code, description) values
  ('SYSTEM_ADMIN', 'All servers, user management, server registration'),
  ('SERVER_ADMIN', 'All settings of assigned Zabbix servers, action editing'),
  ('OPERATOR',     'Edit hosts and items in permitted host groups'),
  ('VIEWER',       'Read only in permitted host groups');

create table user_roles (
  user_id   bigint      not null,
  role_code varchar(30) not null,
  primary key (user_id, role_code),
  constraint fk_user_roles_user foreign key (user_id) references users (user_id) on delete cascade,
  constraint fk_user_roles_role foreign key (role_code) references roles (role_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

create table zabbix_servers (
  server_id            bigint        not null auto_increment primary key,
  name                 varchar(100)  not null,
  description          varchar(500)  null,
  db_url               varchar(500)  not null,
  db_read_user         varchar(100)  not null,
  -- AES-256-GCM: nonce || ciphertext || tag
  db_read_password     varbinary(512) not null,
  db_write_user        varchar(100)  null,
  db_write_password    varbinary(512) null,
  -- detected from dbversion
  zabbix_version       varchar(10)   null,
  db_version_mandatory int           null,
  read_only            boolean       not null default true,
  enabled              boolean       not null default true,
  last_status          varchar(20)   null,
  last_checked_at      datetime(3)   null,
  created_at           datetime(3)   not null,
  updated_at           datetime(3)   not null,
  constraint uk_zabbix_servers_name unique (name)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

-- Servers assigned to a SERVER_ADMIN
create table user_servers (
  user_id   bigint not null,
  server_id bigint not null,
  primary key (user_id, server_id),
  constraint fk_user_servers_user   foreign key (user_id)   references users (user_id) on delete cascade,
  constraint fk_user_servers_server foreign key (server_id) references zabbix_servers (server_id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

-- Host groups permitted to OPERATOR / VIEWER
create table user_scopes (
  scope_id          bigint       not null auto_increment primary key,
  user_id           bigint       not null,
  server_id         bigint       not null,
  hostgroup_id      bigint       not null,
  -- name at the time of assignment, for display when the group is gone
  hostgroup_name    varchar(255) not null,
  include_subgroups boolean      not null default false,
  created_at        datetime(3)  not null,
  constraint uk_user_scopes unique (user_id, server_id, hostgroup_id),
  constraint fk_user_scopes_user   foreign key (user_id)   references users (user_id) on delete cascade,
  constraint fk_user_scopes_server foreign key (server_id) references zabbix_servers (server_id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

-- Audit trail. No foreign keys: records outlive users and servers.
create table audit_log (
  audit_id    bigint       not null auto_increment primary key,
  occurred_at datetime(3)  not null,
  user_id     bigint       null,
  login_id    varchar(100) null,
  client_ip   varchar(45)  null,
  server_id   bigint       null,
  target_type varchar(50)  not null,
  target_id   varchar(100) null,
  action      varchar(30)  not null,
  result      varchar(20)  not null,
  detail      json         null,
  index ix_audit_log_time (occurred_at),
  index ix_audit_log_user (user_id, occurred_at),
  index ix_audit_log_server (server_id, occurred_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

create table login_attempts (
  attempt_id   bigint       not null auto_increment primary key,
  login_id     varchar(100) not null,
  client_ip    varchar(45)  null,
  attempted_at datetime(3)  not null,
  success      boolean      not null,
  index ix_login_attempts_login (login_id, attempted_at),
  index ix_login_attempts_ip (client_ip, attempted_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;

create table dashboard_cache (
  server_id    bigint      not null primary key,
  collected_at datetime(3) not null,
  payload      json        not null,
  constraint fk_dashboard_cache_server foreign key (server_id) references zabbix_servers (server_id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin;
