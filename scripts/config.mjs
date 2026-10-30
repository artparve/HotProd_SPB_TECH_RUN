import {
  copyFileSync,
  chmodSync,
  constants,
  readFileSync,
  statSync,
} from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseEnv } from "node:util";

export const ROOT = fileURLToPath(new URL("../", import.meta.url));
export const SECRET_KEYS = ["MONGO_ROOT_PASSWORD","MONGO_PASSWORD","MONGO_KEYFILE_SECRET","INTERNAL_SERVICE_TOKEN","ADMIN_PASSWORD_HASH"];

export function initConfig(root = ROOT) {
  for (const [source, target] of [
    ["env/.env.example", "env/.env"],
    ["env/secret.env.example", "env/secret.env"],
    [
      "docker/docker-compose.override.yml.example",
      "docker/docker-compose.override.yml",
    ],
  ]) {
    try {
      copyFileSync(
        resolve(root, source),
        resolve(root, target),
        constants.COPYFILE_EXCL,
      );
      if (target === "env/secret.env") chmodSync(resolve(root, target), 0o600);
      console.log(`Created: ${target}`);
    } catch (error) {
      if (error.code !== "EEXIST" || !statSync(resolve(root, target)).isFile())
        throw error;
    }
  }
}

export function localEnvironment(root = ROOT, inherited = process.env) {
  const defaults = parseEnv(
    readFileSync(resolve(root, "env/.env.example"), "utf8"),
  );
  const settings = parseEnv(readFileSync(resolve(root, "env/.env"), "utf8"));
  const secrets = parseEnv(
    readFileSync(resolve(root, "env/secret.env"), "utf8"),
  );
  if ([...SECRET_KEYS, "DB_PASSWORD"].some((key) => settings[key])) {
    throw new Error(
      "Move credentials from env/.env to env/secret.env; replace obsolete DB_PASSWORD with Mongo credentials",
    );
  }
  if (Object.keys(secrets).some((key) => !SECRET_KEYS.includes(key))) {
    throw new Error(
      "env/secret.env may contain only the documented Mongo, LLM and admin secrets",
    );
  }
  return { ...defaults, ...settings, ...secrets, ...inherited };
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  try {
    if (process.argv[2] !== "init")
      throw new Error("Usage: node scripts/config.mjs init");
    initConfig();
  } catch {
    console.error(
      "Configuration initialization failed; existing files were not overwritten.",
    );
    process.exitCode = 1;
  }
}
