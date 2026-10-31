import { delimiter, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { ROOT, SECRET_KEYS, localEnvironment } from "./config.mjs";

export function localCommand(name, env, root = ROOT) {
  const data = resolve(root, env.DATA_DIR || "data");
  const dataPath = (key, fallback) =>
    resolve(root, env[key] || resolve(data, fallback));
  if (name === "llm" || name === "estimator") {
    const serviceEnv = { ...env };
    for (const key of Object.keys(serviceEnv))
      if (
        (SECRET_KEYS.includes(key) && key !== "INTERNAL_SERVICE_TOKEN") ||
        key.startsWith("MONGO_") ||
        key.startsWith("ADMIN_")
      )
        delete serviceEnv[key];
    return {
      command:
        env.PYTHON || (process.platform === "win32" ? "python" : "python3"),
      args: ["-m", "repaircalc_llm"],
      cwd: root,
      env: {
        ...serviceEnv,
        DATA_DIR: data,
        CLASSIFICATION_TAXONOMY: dataPath(
          "CLASSIFICATION_TAXONOMY",
          "classification/taxonomy.json",
        ),
        CLASSIFICATION_PROMPT: dataPath(
          "CLASSIFICATION_PROMPT",
          "classification/prompt.txt",
        ),
        PLAN_DATA_DIR: dataPath("PLAN_DATA_DIR", "plans"),
        OLLAMA_URL: env.LOCAL_OLLAMA_URL || "http://127.0.0.1:11434",
        LLM_ADDRESS: env.LOCAL_LLM_ADDRESS || "127.0.0.1",
        PYTHONPATH: ["llm", "llm/classification", "llm/estimation"]
          .map((p) => resolve(root, p))
          .concat(env.PYTHONPATH || [])
          .join(delimiter),
      },
    };
  }
  if (!env.MONGO_PASSWORD?.trim())
    throw new Error("Set MONGO_PASSWORD in env/secret.env");
  const imports = {
    "backend-import-catalog": [
      "--repaircalc.command=import-catalog",
      "--repaircalc.catalog.file=" +
        dataPath("CATALOG_FILE", "catalog-v1.json"),
      "--repaircalc.catalog.mappings=" +
        dataPath("CATALOG_MAPPINGS_FILE", "work-mappings-v1.json"),
    ],
    "backend-import-rules": [
      "--repaircalc.command=import-rules",
      "--repaircalc.rules.file=" + dataPath("RULES_FILE", "rules-v1.json"),
    ],
    "backend-import-inspirations": ["--repaircalc.command=import-inspirations"],
    backend: [],
  };
  if (!Object.hasOwn(imports, name))
    throw new Error("Unknown local service command");
  const serviceEnv = {
    ...env,
    DATA_DIR: data,
    CLASSIFICATION_TAXONOMY: dataPath(
      "CLASSIFICATION_TAXONOMY",
      "classification/taxonomy.json",
    ),
    INSPIRATIONS_DIR: dataPath("INSPIRATIONS_DIR", "inspirations"),
    INSPIRATIONS_MANIFEST: dataPath(
      "INSPIRATIONS_MANIFEST",
      "inspirations/manifest.json",
    ),
  };
  delete serviceEnv.MONGO_ROOT_PASSWORD;
  delete serviceEnv.MONGO_KEYFILE_SECRET;
  return {
    command: env.JAVA_BIN || "java",
    args: [
      "-jar",
      "target/repaircalc-backend-0.1.0-SNAPSHOT.jar",
      ...imports[name],
    ],
    cwd: resolve(root, "backend"),
    env: serviceEnv,
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
