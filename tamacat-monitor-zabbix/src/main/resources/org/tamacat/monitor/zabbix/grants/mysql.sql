-- Accounts for tamacat-monitor on a Zabbix DB (MySQL 8.0+), T-409.
-- Run as a MySQL administrator. Replace ${db} (Zabbix schema, usually zabbix), ${host} (the host
-- tamacat-monitor connects from, e.g. 10.0.0.% ) and the two passwords.
-- The same file is used by the integration tests, so keep one statement per line block ending with ";".
-- CREATE USER IF NOT EXISTS keeps the password of an existing account: use ALTER USER to change it.

-- read account: search and dashboards
CREATE USER IF NOT EXISTS 'tmon_read'@'${host}' IDENTIFIED BY '${read_password}';
GRANT SELECT ON `${db}`.* TO 'tmon_read'@'${host}';

-- write account: only the tables tamacat-monitor changes. Add grants here when a writer is added.
CREATE USER IF NOT EXISTS 'tmon_write'@'${host}' IDENTIFIED BY '${write_password}';
GRANT SELECT ON `${db}`.* TO 'tmon_write'@'${host}';
-- id allocation (SELECT ... FOR UPDATE needs UPDATE)
GRANT INSERT, UPDATE ON `${db}`.`ids` TO 'tmon_write'@'${host}';
-- host groups (T-404). Rows of rights / tag_filter / maintenances_groups / widget_field are removed by
-- ON DELETE CASCADE; MySQL does not check privileges for cascaded changes.
GRANT INSERT, UPDATE, DELETE ON `${db}`.`hstgrp` TO 'tmon_write'@'${host}';
-- hosts (T-405): host row, group membership, interfaces, tags
GRANT INSERT, UPDATE, DELETE ON `${db}`.`hosts` TO 'tmon_write'@'${host}';
GRANT INSERT, DELETE ON `${db}`.`hosts_groups` TO 'tmon_write'@'${host}';
GRANT INSERT, UPDATE, DELETE ON `${db}`.`interface` TO 'tmon_write'@'${host}';
GRANT INSERT, DELETE ON `${db}`.`host_tag` TO 'tmon_write'@'${host}';
-- items (T-406): item row, runtime row (values maintained by the Zabbix server), tags
GRANT INSERT, UPDATE, DELETE ON `${db}`.`items` TO 'tmon_write'@'${host}';
GRANT INSERT ON `${db}`.`item_rtdata` TO 'tmon_write'@'${host}';
GRANT INSERT, DELETE ON `${db}`.`item_tag` TO 'tmon_write'@'${host}';
-- trigger actions (T-407): action row, conditions, operations with messages and recipients
GRANT INSERT, UPDATE, DELETE ON `${db}`.`actions` TO 'tmon_write'@'${host}';
GRANT INSERT, UPDATE, DELETE ON `${db}`.`conditions` TO 'tmon_write'@'${host}';
GRANT INSERT, UPDATE, DELETE ON `${db}`.`operations` TO 'tmon_write'@'${host}';
GRANT INSERT, UPDATE, DELETE ON `${db}`.`opmessage` TO 'tmon_write'@'${host}';
GRANT INSERT, DELETE ON `${db}`.`opmessage_grp` TO 'tmon_write'@'${host}';
GRANT INSERT, DELETE ON `${db}`.`opmessage_usr` TO 'tmon_write'@'${host}';
-- deletes (T-408): host / item with triggers, graphs and housekeeper rows (other rows by ON DELETE CASCADE)
GRANT DELETE ON `${db}`.`hosts_templates` TO 'tmon_write'@'${host}';
GRANT DELETE ON `${db}`.`host_inventory` TO 'tmon_write'@'${host}';
GRANT DELETE ON `${db}`.`item_preproc` TO 'tmon_write'@'${host}';
GRANT DELETE ON `${db}`.`triggers` TO 'tmon_write'@'${host}';
GRANT DELETE ON `${db}`.`functions` TO 'tmon_write'@'${host}';
GRANT DELETE ON `${db}`.`trigger_tag` TO 'tmon_write'@'${host}';
GRANT DELETE ON `${db}`.`trigger_depends` TO 'tmon_write'@'${host}';
GRANT UPDATE, DELETE ON `${db}`.`graphs` TO 'tmon_write'@'${host}';
GRANT DELETE ON `${db}`.`graphs_items` TO 'tmon_write'@'${host}';
GRANT DELETE ON `${db}`.`profiles` TO 'tmon_write'@'${host}';
GRANT INSERT ON `${db}`.`housekeeper` TO 'tmon_write'@'${host}';
-- template link / unlink (T-601): the template's items, triggers and graphs are copied to the host
GRANT INSERT ON `${db}`.`hosts_templates` TO 'tmon_write'@'${host}';
GRANT INSERT, UPDATE ON `${db}`.`triggers` TO 'tmon_write'@'${host}';
GRANT INSERT ON `${db}`.`functions` TO 'tmon_write'@'${host}';
GRANT INSERT ON `${db}`.`trigger_tag` TO 'tmon_write'@'${host}';
GRANT INSERT ON `${db}`.`trigger_depends` TO 'tmon_write'@'${host}';
GRANT INSERT ON `${db}`.`graphs` TO 'tmon_write'@'${host}';
GRANT INSERT ON `${db}`.`graphs_items` TO 'tmon_write'@'${host}';
GRANT INSERT ON `${db}`.`item_preproc` TO 'tmon_write'@'${host}';
-- Zabbix 7.0 and later only: also run grants/mysql-7.0.sql (permission cache tables, item_rtname)
-- Zabbix 8.0 and later only: also run grants/mysql-8.0.sql (template cache tables)
