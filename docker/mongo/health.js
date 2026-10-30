const admin = db.getSiblingDB('admin');
if (!admin.auth(process.env.MONGO_INITDB_ROOT_USERNAME, process.env.MONGO_INITDB_ROOT_PASSWORD)) quit(1);
// The official entrypoint briefly starts a standalone server while creating users.
// Wait for the actual replica-set process before starting the init service.
const options = admin.runCommand({getCmdLineOpts: 1});
const configured = options.parsed?.replication?.replSet ?? options.parsed?.replication?.replSetName;
quit(configured === process.env.MONGO_REPLICA_SET ? 0 : 1);
