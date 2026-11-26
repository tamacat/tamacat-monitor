-- Additional grants for Zabbix 7.0 and later (run after grants/mysql.sql).
-- The host permission cache (hgset / hgset_group / host_hgset / permission) is maintained by tamacat-monitor when
-- the host groups of a host change, as the Zabbix frontend does. These tables do not exist on 6.0.
GRANT INSERT ON `${db}`.`hgset` TO 'tmon_write'@'${host}';
GRANT INSERT ON `${db}`.`hgset_group` TO 'tmon_write'@'${host}';
GRANT INSERT, UPDATE, DELETE ON `${db}`.`host_hgset` TO 'tmon_write'@'${host}';
GRANT INSERT ON `${db}`.`permission` TO 'tmon_write'@'${host}';
-- resolved item names (7.0 keeps them in item_rtname instead of items.name_upper)
GRANT INSERT, UPDATE ON `${db}`.`item_rtname` TO 'tmon_write'@'${host}';
