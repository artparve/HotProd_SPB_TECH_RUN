import {
  copyFileSync,
  chmodSync,
  constants,
  readFileSync,
  existsSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseEnv } from "node:util";

export const ROOT = fileURLToPath(new URL("../", import.meta.url));
export const SECRET_KEYS = [
  "MONGO_ROOT_PASSWORD",
  "MONGO_PASSWORD",
  "MONGO_KEYFILE_SECRET",
  "INTERNAL_SERVICE_TOKEN",
  "ADMIN_PASSWORD_HASH",
];

export function initConfig(root = ROOT) {
  for (const [source, target] of [
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
  syncSecrets(root);
}

export function syncSecrets(root = ROOT) {
  const path = resolve(root, "env/secret.env");
  const template = parseEnv(
    readFileSync(resolve(root, "env/secret.env.example"), "utf8"),
  );
  if (
    Object.keys(template).some((key) => !SECRET_KEYS.includes(key)) ||
    SECRET_KEYS.some((key) => !(key in template)) ||
    Object.values(template).some((value) => value !== "")
  ) {
    throw new Error(
      "Secret template must contain exactly the documented keys with empty values",
    );
  }
  const text = readFileSync(path, "utf8");
  const current = parseEnv(text);
  const obsolete = Object.keys(current).filter(
    (key) => !SECRET_KEYS.includes(key),
  );
  let updated = text;
  if (obsolete.length) {
    // Preserve the original verbatim before removing obsolete single-line keys.
    // Do not rewrite multiline values: an operator must migrate these privately.
    if (Object.values(current).some((value) => /[\r\n\0]/.test(value)))
      throw new Error("Migrate multiline secret values manually");
    const backup = resolve(root, "env/secret.env.legacy");
    copyFileSync(path, backup, constants.COPYFILE_EXCL);
    chmodSync(backup, 0o600);
    updated = text
      .split(/\r?\n/)
      .filter((line) => {
        const key = line.match(
          /^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=/,
        )?.[1];
        return !key || !obsolete.includes(key);
      })
      .join("\n");
    console.log(
      "Obsolete secret keys archived privately in env/secret.env.legacy",
    );
  }
  for (const key of SECRET_KEYS) {
    if (!(key in current))
      updated = updated.replace(/\s*$/, "") + `\n${key}=\n`;
  }
  if (updated !== text) writeFileSync(path, updated, { mode: 0o600 });
  chmodSync(path, 0o600);
}

export function localEnvironment(root = ROOT, inherited = process.env) {
  const defaults = parseEnv(readFileSync(resolve(root, "env/.env"), "utf8"));
  const override = resolve(root, "env/.env.local");
  const settings = existsSync(override)
    ? parseEnv(readFileSync(override, "utf8"))
    : {};
  const secrets = parseEnv(
    readFileSync(resolve(root, "env/secret.env"), "utf8"),
  );
  if (
    [...SECRET_KEYS, "DB_PASSWORD"].some(
      (key) => defaults[key] || settings[key],
    )
  ) {
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
