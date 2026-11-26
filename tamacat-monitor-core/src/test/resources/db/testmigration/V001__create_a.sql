-- create a; with a ';' inside a string
create table a (id bigint primary key, note varchar(50) default 'x;y');
/* block
   comment; */
insert into a (id) values (1);
