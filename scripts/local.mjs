import { delimiter, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { ROOT, SECRET_KEYS, localEnvironment } from "./config.mjs";

export function localCommand(name, env, root = ROOT) {
  if (name === "estimator") {
    const serviceEnv = { ...env };
    for (const key of SECRET_KEYS) delete serviceEnv[key];
    return {
      command:
        env.PYTHON || (process.platform === "win32" ? "python" : "python3"),
      args: [
        "-m",
        "repaircalc_estimator",
        "--backend",
        env.API_UPSTREAM,
        "--host",
        env.LOCAL_OLLAMA_URL,
        "--model",
        env.OLLAMA_MODEL,
        "--num-threads",
        env.OLLAMA_NUM_THREADS,
        "serve",
        "--listen",
        env.ESTIMATOR_ADDRESS,
        "--port",
        env.ESTIMATOR_PORT,
      ],
      cwd: root,
      env: {
        ...serviceEnv,
        PYTHONPATH: [resolve(root, "backend/estimation"), env.PYTHONPATH]
          .filter(Boolean)
          .join(delimiter),
      },
    };
  }
  if (!env.DB_PASSWORD?.trim())
    throw new Error("Set DB_PASSWORD in env/secret.env");
  const imports = {
    "backend-import-catalog": [
      "--repaircalc.command=import-catalog",
      "--repaircalc.catalog.file=data/catalog-v1.json",
      "--repaircalc.catalog.mappings=data/work-mappings-v1.json",
    ],
    "backend-import-rules": [
      "--repaircalc.command=import-rules",
      "--repaircalc.rules.file=data/rules-v1.json",
    ],
    backend: [],
  };
  if (!Object.hasOwn(imports, name))
    throw new Error("Unknown local service command");
  return {
    command: "java",
    args: [
      "-jar",
      "target/repaircalc-backend-0.1.0-SNAPSHOT.jar",
      ...imports[name],
    ],
    cwd: resolve(root, "backend"),
    env,
  };
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  try {
    const { command, args, cwd, env } = localCommand(
      process.argv[2],
      localEnvironment(),
    );
    const result = spawnSync(command, args, {
      cwd,
      env,
      stdio: "inherit",
      shell: false,
    });
    process.exitCode = result.error ? 1 : (result.status ?? 1);
  } catch (error) {
    // Never print file contents or process environments.
    console.error(
      error.code
        ? "Local startup failed; check configuration files and installed dependencies."
        : error.message,
    );
    process.exitCode = 1;
  }
}
