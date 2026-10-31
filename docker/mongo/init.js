// Executed inside the primary's container. Secrets are read from environment, never argv.
const admin = db.getSiblingDB('admin');
let authenticated = admin.auth(process.env.MONGO_ROOT_USER, process.env.MONGO_ROOT_PASSWORD);
if (!authenticated) throw new Error('Mongo root authentication failed');
try { rs.status(); } catch (error) {
  if (error.code !== 94) throw error;
  rs.initiate({_id: process.env.MONGO_REPLICA_SET, members: [{_id: 0, host: process.env.MONGO_REPLICA_HOST}]});
}
const deadline = Date.now() + Number(process.env.MONGO_INIT_TIMEOUT_MS || 60000);
while (!db.hello().isWritablePrimary) {
  if (Date.now() > deadline) throw new Error('Replica set primary timeout');
  sleep(500);
}
const application = db.getSiblingDB(process.env.MONGO_DATABASE);
if (!application.getUser(process.env.MONGO_USER)) {
  application.createUser({user: process.env.MONGO_USER, pwd: process.env.MONGO_PASSWORD,
    roles: [{role: 'readWrite', db: process.env.MONGO_DATABASE}]});
}
print('Mongo replica set and application user ready');
