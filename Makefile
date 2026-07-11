.DEFAULT_GOAL := help

.PHONY: help
help: ## このヘルプを表示
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ---------- backend (root Gradle project) ----------

.PHONY: backend-up
backend-up: ## backend を起動 (port 8081)
	mise exec -- ./gradlew bootRun

.PHONY: backend-test
backend-test: ## backend テスト
	mise exec -- ./gradlew test

.PHONY: backend-lint
backend-lint: ## backend ktlint check
	mise exec -- ./gradlew ktlintCheck

.PHONY: backend-fmt
backend-fmt: ## backend ktlint 自動整形
	mise exec -- ./gradlew ktlintFormat

.PHONY: backend-build
backend-build: ## backend build (test skip)
	mise exec -- ./gradlew build -x test

# ---------- demo-service (subdirectory) ----------

.PHONY: demo-up
demo-up: ## demo-service を起動 (port 8082)
	cd demo-service && mise exec -- ./gradlew bootRun

.PHONY: demo-test
demo-test: ## demo-service テスト
	cd demo-service && mise exec -- ./gradlew test

# ---------- frontend ----------

.PHONY: frontend-up
frontend-up: ## Next.js dev server (port 3100)
	cd frontend && mise exec -- pnpm dev

.PHONY: frontend-typecheck
frontend-typecheck: ## TypeScript type check
	cd frontend && mise exec -- pnpm typecheck

.PHONY: frontend-build
frontend-build: ## Next.js production build
	cd frontend && mise exec -- pnpm build

# ---------- docker ----------

.PHONY: docker-backend
docker-backend: ## Docker: backend image
	docker build -t devops-incident-agent:latest .

.PHONY: docker-demo
docker-demo: ## Docker: demo-service image
	docker build -t demo-service:latest ./demo-service

# ---------- clean ----------

.PHONY: clean
clean: ## 全 build 成果物削除
	mise exec -- ./gradlew clean
	cd demo-service && mise exec -- ./gradlew clean
	rm -rf frontend/.next frontend/node_modules

# ---------- deploy hint ----------

.PHONY: deploy-hint
deploy-hint: ## Cloud Run / Vercel デプロイの手順
	@echo "  1. gcloud run deploy devops-incident-agent --source ."
	@echo "  2. gcloud run deploy demo-service --source ./demo-service --region us-central1"
	@echo "  3. vercel --prod (frontend)"
	@echo "詳細は docs/DEPLOY.md を参照 (次 Phase で作成)"
