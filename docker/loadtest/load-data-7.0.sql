-- Load test data for a DISPOSABLE Zabbix 7.0 test DB (T-503). Never run on a real Zabbix DB.
-- A helper table tmon_load_seq (0..999999) is created and dropped (MySQL cannot join a temporary table to itself).
-- ids from 900000000000 so that docker/loadtest/cleanup.sql can remove everything. The ids table is not
-- changed: the Zabbix API allocates far below this range, so no collision during the test.
-- 2,000 hosts x 10 trapper items (not polled), 6,000,000 history_uint rows, 2,000 triggers,
-- 1,000,000 events (20,000 open problems + 200,000 resolved), 500,000 alerts over 30 days.
set session cte_max_recursion_depth = 2000000;
set @base = 900000000000;
set @now = unix_timestamp();

drop table if exists tmon_load_seq;
create table tmon_load_seq (n int primary key);
insert into tmon_load_seq (n)
  select a.d + b.d * 10 + c.d * 100 + d.d * 1000 + e.d * 10000 + f.d * 100000
  from (select 0 d union all select 1 union all select 2 union all select 3 union all select 4 union all select 5
        union all select 6 union all select 7 union all select 8 union all select 9) a
  cross join (select 0 d union all select 1 union all select 2 union all select 3 union all select 4 union all select 5
        union all select 6 union all select 7 union all select 8 union all select 9) b
  cross join (select 0 d union all select 1 union all select 2 union all select 3 union all select 4 union all select 5
        union all select 6 union all select 7 union all select 8 union all select 9) c
  cross join (select 0 d union all select 1 union all select 2 union all select 3 union all select 4 union all select 5
        union all select 6 union all select 7 union all select 8 union all select 9) d
  cross join (select 0 d union all select 1 union all select 2 union all select 3 union all select 4 union all select 5
        union all select 6 union all select 7 union all select 8 union all select 9) e
  cross join (select 0 d union all select 1 union all select 2 union all select 3 union all select 4 union all select 5
        union all select 6 union all select 7 union all select 8 union all select 9) f;

insert into hstgrp (groupid, name, flags, uuid, type) values (@base, 'tmon-load', 0, '', 0);

insert into hosts (hostid, host, name, name_upper, status, flags, description, uuid)
  select @base + n, concat('load-', lpad(n, 4, '0')), concat('load-', lpad(n, 4, '0')), concat('LOAD-', lpad(n, 4, '0')),
         0, 0, '', ''
  from tmon_load_seq where n between 1 and 2000;
insert into hosts_groups (hostgroupid, hostid, groupid) select @base + n, @base + n, @base from tmon_load_seq where n between 1 and 2000;

insert into items (itemid, hostid, name, key_, type, value_type, delay, history, trends, units, params, query_fields,
                   posts, headers, description, status, flags, uuid)
  select @base + h.n * 10 + k.n, @base + h.n, concat('load item ', k.n), concat('load.k[', k.n, ']'), 2, 3, '0', '7d',
         '365d', '', '', '', '', '', '', 0, 0, ''
  from tmon_load_seq h join tmon_load_seq k on k.n < 10 where h.n between 1 and 2000;
insert into item_rtdata (itemid) select itemid from items where itemid >= @base;

-- 300 values per item, one per minute
insert into history_uint (itemid, clock, value, ns)
  select i.itemid, @now - m.n * 60, (i.itemid + m.n) % 1000, 0
  from items i join tmon_load_seq m on m.n < 300 where i.itemid >= @base;

insert into triggers (triggerid, expression, description, priority, status, value, flags, uuid, comments)
  select @base + n, concat('{', @base + n, '}>0'), concat('load trigger ', n), n % 6, 0, 1, 0, '', ''
  from tmon_load_seq where n between 1 and 2000;
insert into functions (functionid, itemid, triggerid, name, parameter)
  select @base + n, @base + n * 10, @base + n, 'last', '$' from tmon_load_seq where n between 1 and 2000;

insert into events (eventid, source, object, objectid, clock, value, acknowledged, ns, name, severity)
  select @base + n, 0, 0, @base + (n % 2000) + 1, @now - (n * 2.592) div 1, 1, n % 3 = 0, 0,
         concat('load problem ', n % 2000), n % 6
  from tmon_load_seq where n < 1000000;

-- 20,000 open problems, 200,000 resolved (recovery events are other load events)
insert into problem (eventid, source, object, objectid, clock, ns, name, severity, acknowledged, r_eventid, r_clock, r_ns)
  select @base + n, 0, 0, @base + (n % 2000) + 1, @now - (n * 2.592) div 1, 0, concat('load problem ', n % 2000), n % 6,
         n % 3 = 0, null, 0, 0
  from tmon_load_seq where n < 20000;
insert into problem (eventid, source, object, objectid, clock, ns, name, severity, acknowledged, r_eventid, r_clock, r_ns)
  select @base + n, 0, 0, @base + (n % 2000) + 1, @now - (n * 2.592) div 1, 0, concat('load problem ', n % 2000), n % 6,
         1, @base + n + 500000, @now - (n * 2.592) div 1 + 60, 0
  from tmon_load_seq where n between 20000 and 219999;

insert into alerts (alertid, actionid, eventid, clock, sendto, subject, message, status, retries, error, esc_step,
                    alerttype, parameters)
  select @base + n, 3, @base + n * 2, @now - (n * 5.184) div 1, concat('ops', n % 50, '@example.com'), 'load', 'load', n % 3,
         0, '', 1, 0, '{}'
  from tmon_load_seq where n < 500000;

drop table tmon_load_seq;
