#!/bin/bash
# Online recovery 2nd stage script

set -o xtrace

MAIN_NODE_PGDATA="$1"              # main dabatase cluster
DEST_NODE_HOST="$2"                 # hostname of the DB node to be recovered
DEST_NODE_PGDATA="$3"              # database cluster of the DB node to be recovered
MAIN_NODE_PORT="$4"                 # PostgreSQL port number

PGHOME=/usr/pgsql-14
ARCHIVEDIR=/var/lib/pgsql/archivedir  # archive log directory
POSTGRESQL_STARTUP_USER=postgres
SSH_KEY_FILE=id_rsa_pgpool
SSH_OPTIONS="-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ~/.ssh/${SSH_KEY_FILE}"

# Force to flush current value of sequences to xlog
${PGHOME}/bin/psql -p $PORT -t -c 'SELECT datname FROM pg_database WHERE NOT datistemplate AND datallowconn' template1|
while read i
do
  if [ "$i" != "" ]; then
    psql -p $PORT -c "SELECT setval(oid, nextval(oid)) FROM pg_class WHERE relkind = 'S'" $i
  fi
done

psql -p $PORT -c "SELECT pgpool_switch_xlog('$ARCHIVEDIR')" template1

# start target server as a streaming replication standby server
ssh -T ${SSH_OPTIONS} ${POSTGRESQL_STARTUP_USER}@$DEST_NODE_HOST "
        $PGHOME/bin/pg_ctl -l /dev/null -w -D $DEST_NODE_PGDATA promote
"
