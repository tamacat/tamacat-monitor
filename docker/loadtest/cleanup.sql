-- Removes the load test data of load-data-7.0.sql (ids from 900000000000).
set @base = 900000000000;
delete from alerts where alertid >= @base;
delete from problem where eventid >= @base;
delete from events where eventid >= @base;
delete from functions where functionid >= @base;
delete from triggers where triggerid >= @base;
delete from history_uint where itemid >= @base;
delete from item_rtdata where itemid >= @base;
delete from items where itemid >= @base;
delete from hosts_groups where hostgroupid >= @base;
delete from hosts where hostid >= @base;
delete from hstgrp where groupid >= @base;
delete from housekeeper where value >= @base;
