.DEFAULT_GOAL := help

NODE ?= node
NPM ?= npm
DOCKER ?= docker

COMPOSE_CLI ?= $(DOCKER) compose
COMPOSE = $(COMPOSE_CLI) --project-directory docker --env-file env/.env --env-file env/secret.env -f docker/docker-compose.yml -f docker/docker-compose.override.yml
COPY_CONFIG = $(NODE) -e "const fs = require('node:fs'); try { fs.copyFileSync(process.argv[1], process.argv[2], fs.constants.COPYFILE_EXCL); console.log('Created: ' + process.argv[2]); } catch (error) { if (error.code === 'EEXIST' && fs.statSync(process.argv[2]).isFile()) process.exit(0); throw error; }"

.PHONY: help init init-config install dev build preview format typecheck test check config-test e2e e2e-install dependencies backend backend-import-catalog backend-import-rules estimator up down logs ps config image release release-image

help:
	@echo "RepairCalc: GNU Make + Node.js 22.12+ LTS (no Python or Bash required)"
	@echo "init          - create missing local config and install dependencies"
	@echo "init-config   - create env/.env, env/secret.env and local Compose override"
	@echo "install       - npm ci using the lockfile"
	@echo "dev / preview - start the development or built SPA server"
	@echo "build         - typecheck and production frontend/dist"
	@echo "check / test  - all checks or unit/component/contract tests"
	@echo "e2e-install   - download Playwright Chromium once"
	@echo "e2e           - desktop and mobile browser tests"
	@echo "up / down     - start or stop full Docker stack"
	@echo "dependencies  - start local PostgreSQL only (127.0.0.1:55432 by default)"
	@echo "backend / estimator - run local Java jar or Python using both env files"
	@echo "logs / ps     - container logs or status"
	@echo "config        - validate Compose without starting containers"
	@echo "image         - build application Docker images using env/.env"
	@echo "release       - clean dependency install, checks and production build"
	@echo "release-image - release plus Docker image; no archive or deployment"

init: init-config
	"$(MAKE)" install

init-config: env/.env env/secret.env docker/docker-compose.override.yml

# File rules avoid requiring Node on a Docker-only host once configs exist.
# Exclusive copies never replace existing configuration, even across processes.
env/.env:
	@$(COPY_CONFIG) env/.env.example "$@"

env/secret.env:
	@$(NODE) scripts/config.mjs init

docker/docker-compose.override.yml:
	@$(COPY_CONFIG) docker/docker-compose.override.yml.example "$@"

install:
	$(NPM) --prefix frontend ci --no-fund

dev build preview check: | env/.env
up config image dependencies: | env/.env env/secret.env docker/docker-compose.override.yml
backend backend-import-catalog backend-import-rules estimator: | env/.env env/secret.env

backend backend-import-catalog backend-import-rules estimator:
	$(NODE) scripts/local.mjs $@

dev build preview:
	$(NPM) --prefix frontend run $@

format:
	$(NPM) --prefix frontend run format

typecheck:
	$(NPM) --prefix frontend run typecheck

test:
	$(NPM) --prefix frontend test

check:
	"$(MAKE)" config-test
	$(NPM) --prefix frontend run format:check
	$(NPM) --prefix frontend run typecheck
	"$(MAKE)" test
	$(NPM) --prefix frontend run build

config-test:
	$(NODE) --test scripts/config.test.mjs

e2e-install:
	$(NPM) --prefix frontend exec -- playwright install chromium

e2e:
	$(NPM) --prefix frontend run test:e2e

up:
	$(COMPOSE) up --build --detach --wait

dependencies:
	$(COMPOSE) up --detach --wait postgres

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs --follow --tail 100

ps:
	$(COMPOSE) ps

config:
	$(COMPOSE) config --quiet

image:
	$(COMPOSE) build

# Recursive makes are intentionally sequential, including under make -j:
# npm ci must finish before any check reads node_modules.
release: | env/.env
	"$(MAKE)" install
	"$(MAKE)" check

release-image:
	"$(MAKE)" release
	"$(MAKE)" image
