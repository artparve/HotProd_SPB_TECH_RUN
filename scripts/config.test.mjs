import { test } from "node:test";
import assert from "node:assert/strict";
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { resolve } from "node:path";
import { tmpdir } from "node:os";
import { ROOT, initConfig, localEnvironment } from "./config.mjs";
import { localCommand } from "./local.mjs";

function fixture(t) {
  const base = tmpdir();
  const root = mkdtempSync(resolve(base, "repaircalc-config-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(resolve(root, "env"));
  mkdirSync(resolve(root, "docker"));
  for (const file of [
    "env/.env.example",
    "env/secret.env.example",
    "docker/docker-compose.override.yml.example",
  ]) {
    copyFileSync(resolve(ROOT, file), resolve(root, file));
  }
  initConfig(root);
  return root;
}

test("initialization creates both env files without overwriting existing configuration", (t) => {
  const root = fixture(t);
  assert.ok(existsSync(resolve(root, "env/.env")));
  assert.ok(existsSync(resolve(root, "env/secret.env")));
  writeFileSync(resolve(root, "env/.env"), "DEV_PORT=5199\n");
  writeFileSync(
    resolve(root, "env/secret.env"),
    "MONGO_PASSWORD='private-$value'\n",
  );
  initConfig(root);
  assert.equal(
    readFileSync(resolve(root, "env/.env"), "utf8"),
    "DEV_PORT=5199\n",
  );
  assert.equal(
    readFileSync(resolve(root, "env/secret.env"), "utf8"),
    "MONGO_PASSWORD='private-$value'\n",
  );
  if (process.platform !== "win32")
    assert.equal(statSync(resolve(root, "env/secret.env")).mode & 0o777, 0o600);
});

test("local Java/Python configuration merges defaults, public settings, secrets, then process env", (t) => {
  const root = fixture(t);
  writeFileSync(
    resolve(root, "env/.env"),
    "SERVER_PORT=8090\nADMIN_USERNAME=operator\n",
  );
  writeFileSync(
    resolve(root, "env/secret.env"),
    "MONGO_PASSWORD='private-$value'\nADMIN_PASSWORD_HASH='pbkdf2-sha256$600000$salt$hash'\n",
  );
  const env = localEnvironment(root, { SERVER_PORT: "8091" });
  assert.equal(env.SERVER_PORT, "8091");
  assert.equal(env.MONGO_PASSWORD, "private-$value");
  assert.equal(env.ADMIN_PASSWORD_HASH, "pbkdf2-sha256$600000$salt$hash");
  assert.equal(env.MONGO_USER, "repaircalc");
  assert.equal(env.ADMIN_USERNAME, "operator");
  assert.equal(env.SPRING_PROFILES_ACTIVE, "local");
});

test("legacy secrets in public env fail safely rather than silently replacing credentials", (t) => {
  const root = fixture(t);
  writeFileSync(resolve(root, "env/.env"), "MONGO_PASSWORD=private-value\n");
  assert.throws(
    () => localEnvironment(root, {}),
    (error) => !error.message.includes("private-value"),
  );
});

test("secret file cannot introduce client-exposed VITE variables or arbitrary configuration", (t) => {
  const root = fixture(t);
  writeFileSync(
    resolve(root, "env/secret.env"),
    "VITE_PRIVATE_TOKEN=private-value\n",
  );
  assert.throws(() => localEnvironment(root, {}), /may contain only/);
});

test("local services receive credentials through child env, never command arguments", (t) => {
  const root = fixture(t);
  writeFileSync(resolve(root, "env/secret.env"), "MONGO_PASSWORD=private-value\n");
  const env = localEnvironment(root, {});
  const java = localCommand("backend", env, root);
  const python = localCommand("estimator", env, root);
  assert.equal(java.command, "java");
  assert.equal(java.env.MONGO_PASSWORD, "private-value");
  assert.equal(python.env.MONGO_PASSWORD, undefined);
  assert.equal(python.env.ADMIN_PASSWORD_HASH, undefined);
  assert.equal(java.cwd, resolve(root, "backend"));
  assert.equal(python.env.OLLAMA_URL, "http://127.0.0.1:11434");
  assert.equal(python.env.LLM_PORT, "8010");
  assert.ok(
    ![...java.args, ...python.args].join(" ").includes("private-value"),
  );
  assert.throws(
    () => localCommand("backend", { MONGO_PASSWORD: "" }),
    /secret.env/,
  );
});

test("Vite configuration never opens the secret file", () => {
  const vite = readFileSync(resolve(ROOT, "frontend/vite.config.ts"), "utf8");
  assert.ok(!vite.includes("secret.env"));
});
