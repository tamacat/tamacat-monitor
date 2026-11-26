#!/bin/sh
# Loads the Zabbix MySQL schema into an empty test database (mysql image, /docker-entrypoint-initdb.d).
#
# The custom Zabbix server images (tamacat/zabbix-server-mysql:<X.Y>-latest) do not create the schema
# on first start, unlike the official images. The schema comes from the custom build's source tree,
# bind-mounted read-only at /zabbix-schema-src (see docker/compose.yml, ZABBIXnn_SCHEMA_DIR).
# schema.sql must run before images.sql / data.sql, so the order is explicit here.
# Runs only when the database volume is empty. Test databases only.

set -eu

SRC_DIR="/zabbix-schema-src"

mysql --user=root --password="${MYSQL_ROOT_PASSWORD}" -e "SET GLOBAL log_bin_trust_function_creators=1;"
for f in schema.sql images.sql data.sql; do
	if [ ! -f "${SRC_DIR}/${f}" ]; then
		echo "**** ERROR: ${SRC_DIR}/${f} not found (set ZABBIXnn_SCHEMA_DIR to <custom build>/sources/zabbix-<version>/database/mysql)" >&2
		exit 1
	fi
	echo "**** Applying ${f}..."
	mysql --user=root --password="${MYSQL_ROOT_PASSWORD}" "${MYSQL_DATABASE}" < "${SRC_DIR}/${f}"
done
echo "**** Zabbix schema load complete."
