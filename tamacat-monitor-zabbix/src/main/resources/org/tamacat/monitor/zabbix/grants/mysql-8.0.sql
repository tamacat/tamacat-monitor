-- Additional grants for Zabbix 8.0 and later (run after grants/mysql.sql and grants/mysql-7.0.sql).
-- 8.0 keeps a template cache of hosts and items; new hosts and items get their rows (removed by
-- ON DELETE CASCADE). Housekeeper rows of deleted items and triggers are written by DB triggers.
GRANT INSERT, DELETE ON `${db}`.`host_template_cache` TO 'tmon_write'@'${host}';
GRANT INSERT, DELETE ON `${db}`.`item_template_cache` TO 'tmon_write'@'${host}';
-- template links (T-601): runtime rows of the copied triggers
GRANT INSERT ON `${db}`.`trigger_rtdata` TO 'tmon_write'@'${host}';
