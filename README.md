# devops-incident-agent

オンコール当番の初動を助ける障害対応 AI エージェント。Web 画面か Slack から障害の症状を伝えると、Gemini がログ、直近のデプロイ、過去の障害報告を横断して調べ、原因の仮説と推奨アクションを返す。

## 何をするアプリか

サービスで障害が起きたとき、当番のエンジニアが最初にやることは決まっている。ログを見る、直近のデプロイを疑う、過去に同じ障害がなかったか探す。ただし夜中に叩き起こされた状態でこの 3 つを 5〜10 分でこなすのはつらい。

このアプリに症状 (タイトル、深刻度、サービス名) を渡すと、Gemini がツール呼び出し (function calling) で次の 4 種類の調査ツールを必要に応じて使い分ける。

- **query_logs**: BigQuery でアプリケーションログを SQL 集計し、多発しているエラーの傾向を返す
- **search_recent_changes**: GitHub Search API で直近にマージされた PR を返す
- **search_postmortem**: pgvector で過去の障害報告を意味検索する
- **codebase_tour / convention_extract / extract_symbols**: 接続した MCP サーバー経由でコードベースの構造を取る (任意)

調査結果をまとめて「原因はおそらく PR #1234 の DB 接続プール変更。接続の閉じ漏れの再発なので、まず再起動で時間を稼ぐ」のような回答を JSON で返す。推論と調査を交互に繰り返す ReAct 方式で最大 5 ステップ。ツールの実行時間と回答までの時間は Prometheus に p50/p95/p99 で記録している。

## 誰が使うか

想定は開発チーム 5〜30 人規模の SaaS 企業のオンコール当番。Rootly や Blameless のような専用サービスを入れるほどではないが、障害の振り返りは Notion や GitHub Discussions に書き溜めている。そのくらいの規模の組織。

## 構成

```
frontend (Next.js, Vercel)
  ├─ IncidentForm  → POST /incidents/agent
  ├─ DiagnosisPanel (仮説 / 推奨アクション / 調査手順)
  └─ ChaosPanel    → demo-service にわざと障害を起こす

backend: devops-incident-agent (Kotlin + Spring Boot 4)
  ├─ IncidentController      (REST + Slack /commands)
  ├─ IncidentAnalysisService (固定 2 ステップの簡易版)
  └─ ReActAgentService       (LLM がツールを選ぶ完全版)
        │
        ├─ GeminiAgentLlm     (com.google.genai:google-genai)
        └─ ToolRegistry
              ├─ QueryLogsTool           → BigQuery (Cloud Logging から転送)
              ├─ SearchRecentChangesTool → GitHub Search API
              ├─ SearchPostmortemTool    → pgvector (Postgres)
              └─ McpTool ×3              → 外部 MCP サーバー (stdio)

demo-service: 仮想の SaaS (Kotlin + Spring Boot 4)
  ├─ /api/auth/login  /api/orders  /api/checkout   (障害注入モードでわざと失敗する)
  ├─ /admin/chaos  → シナリオ切替 (DB_POOL_TIMEOUT / EXTERNAL_500 / DEADLOCK / SLOW_QUERY)
  └─ 標準出力に構造化 JSON ログ
        ↓ (任意: GCP に載せる場合のみ)
Cloud Logging → Log Router → BigQuery (query_logs はこの表を SQL 集計する)
```

依存する外部サービス:

- Gemini API (`gemini-2.5-flash` + `gemini-embedding-001`。無料枠は 1 分 15 リクエスト、1 日 100 万トークン)
- Postgres + pgvector 拡張 (Neon の無料枠で動作確認)
- GitHub API (個人アクセストークンで 1 時間 5000 リクエスト)
- BigQuery + Cloud Logging (ログ集計を有効にする場合)
- Slack ワークスペース (任意)

## 使ったもの

- Kotlin 2.2 / Spring Boot 4 / Java 21
- kotlinx.coroutines / kotlinx.serialization
- Google GenAI Java SDK (`com.google.genai:google-genai`) を Kotlin から直接使用
- Google Cloud BigQuery SDK
- Spring Boot Actuator + Micrometer + Prometheus (p50/p95/p99 のヒストグラム)
- 自前実装の RetryPolicy (429 / 5xx / IOException で指数バックオフとジッター) と CircuitBreaker (CLOSED / OPEN / HALF_OPEN)
- Kotlin 製 MCP クライアントの自作 (公式 SDK がないため JSON-RPC 2.0 over stdio を直接実装)
- Next.js 15 + React 19 + TanStack Query + Tailwind CSS 4
- Docker (マルチステージビルド、JRE と jar だけで約 250 MB)

## 動かす

前提: JDK 21、mise、Docker (任意)、pnpm。

環境変数は次の通り。未設定の項目はスタブ応答や機能の省略に切り替わり、起動自体は通る。

```sh
# LLM
export INCIDENT_LLM_GEMINI_API_KEY=<https://aistudio.google.com/ で取得>

# BigQuery (query_logs を有効化)
export INCIDENT_BIGQUERY_PROJECT_ID=<GCP プロジェクト ID>
export INCIDENT_BIGQUERY_DATASET=logs
export INCIDENT_BIGQUERY_TABLE=run_googleapis_com_stdout
export GOOGLE_APPLICATION_CREDENTIALS=<サービスアカウントの鍵 JSON>

# GitHub (search_recent_changes を有効化)
export INCIDENT_GITHUB_TOKEN=<GitHub 個人アクセストークン>
export INCIDENT_GITHUB_DEFAULT_REPO=<owner/name>

# Postgres + pgvector (search_postmortem を有効化。初回起動時に見本データが入る)
export SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/postgres
export SPRING_DATASOURCE_USERNAME=<user>
export SPRING_DATASOURCE_PASSWORD=<pass>
export INCIDENT_AUTOCONFIGURE_EXCLUDE=   # 空を設定すると JDBC が有効になる

# MCP (codebase_tour など 3 ツールを有効化)
export INCIDENT_MCP_COMMAND=<MCP サーバーの起動コマンド>

# Slack (任意)
export INCIDENT_SLACK_SIGNING_SECRET=<Slack アプリの署名シークレット>
```

起動は Makefile から。

```sh
make help              # ターゲット一覧

make backend-up        # backend (port 8081) を起動
make demo-up           # demo-service (port 8082) を起動
make frontend-up       # Next.js 開発サーバー (port 3100) を起動

make backend-test      # backend のテスト
make demo-test         # demo-service のテスト
make frontend-typecheck

make backend-lint      # ktlint チェック
make backend-fmt       # ktlint 自動整形

make docker-backend    # Docker: backend イメージ
make docker-demo       # Docker: demo-service イメージ
```

Slack 連携を動かす場合は、Slack アプリを作って `POST /slack/commands` をリクエスト先に設定し、署名シークレットを環境変数に入れる。HMAC 署名の検証、5 分以内のリプレイ攻撃対策、タイミング攻撃を防ぐ一定時間比較は実装済み。

## テスト

`make backend-test` (ルートの Gradle) と `make demo-test` (demo-service) で以下を実行する。

**backend**

- LLM 連携部分の動作確認 (Gemini SDK の呼び出し口の検証)
- ReAct ループの状態遷移 (ツール呼び出し → 結果 → 最終回答)
- IncidentStore のメモリ上での検索
- IncidentAnalysisService (固定 2 ステップの簡易版)
- MCP クライアントの結合テスト (JSON-RPC 2.0 の疎通。サーバー binary を指定した時のみ実行)
- Slack の HMAC 署名検証 (有効な署名、期限切れ、改竄、一定時間比較)
- Slack /commands のリクエスト解析と非同期の応答送信
- RetryPolicy (429 / 5xx / IOException で再試行、4xx は即エラー)
- CircuitBreaker の CLOSED / OPEN / HALF_OPEN 遷移
- REST API と 404 の動作

**demo-service**

- 障害注入の切り替え
- 各エンドポイントの正常応答
- 障害注入中に 500 / 502 が返ること

## デプロイ

Render (backend と demo-service) + Vercel (frontend) + Neon (Postgres + pgvector) の組み合わせ。すべて無料枠で動き、クレジットカードの登録も要らない。

1. **Neon**: プロジェクトを作って接続文字列を控える。pgvector 拡張と見本データは backend の初回起動時に自動で入る
2. **Render**: New Web Service でこのリポジトリを選び、2 サービス作る
   - backend: Root Directory はリポジトリ直下 (Dockerfile を自動検出)。環境変数に `INCIDENT_LLM_GEMINI_API_KEY`、Neon の接続情報 (`SPRING_DATASOURCE_URL` など)、`INCIDENT_AUTOCONFIGURE_EXCLUDE` (空) を設定
   - demo-service: Root Directory を `demo-service` に
3. **Vercel**: リポジトリを Import して Root Directory を `frontend` に。`NEXT_PUBLIC_AGENT_API` と `NEXT_PUBLIC_DEMO_API` に Render で発行された URL を設定
4. backend の `INCIDENT_CORS_ALLOWED_ORIGINS` に Vercel の URL を追加

Render の無料枠は 15 分アクセスがないと停止するため、初回アクセスは応答まで 30〜60 秒かかる。

query_logs (BigQuery のログ集計) は Cloud Logging との連携が前提のため、この構成では無効のまま動く。GCP に載せる場合は Cloud Logging → Log Router → BigQuery の転送を一度設定すると有効化できる。

## リポジトリ構成

```
devops-incident-agent/
├── src/                        # backend (Kotlin, Spring Boot 4)
│   ├── main/kotlin/com/example/incident/
│   │   ├── config/             # Spring bean 配線
│   │   ├── domain/             # Incident / Diagnosis / Recommendation
│   │   ├── mcp/                # Kotlin 製 MCP クライアント
│   │   ├── postmortem/         # 障害報告の初期データ投入
│   │   ├── service/            # ReAct ループ / LLM 連携 / リトライ / サーキットブレーカー
│   │   ├── tools/              # 調査ツールの共通インターフェースと各実装
│   │   └── web/                # REST + Slack のコントローラー
│   └── main/resources/
│       ├── application.yml
│       └── db/schema.sql       # pgvector + hnsw インデックス
├── demo-service/               # 独立した Gradle プロジェクト
│   └── src/main/kotlin/com/example/demo/
│       ├── ApiController.kt    # /api/auth/login /orders /checkout
│       ├── AdminController.kt  # /admin/chaos で障害注入を切替
│       └── ChaosState.kt / ScenarioExecutor.kt / SimulatedException.kt
├── frontend/                   # Next.js 15 + React 19 + TanStack Query
│   └── src/
│       ├── app/                # layout / page / providers
│       ├── components/         # IncidentForm / DiagnosisPanel / IncidentList / ChaosPanel
│       └── lib/api.ts          # backend / demo-service を呼ぶクライアント
├── Dockerfile                  # backend (マルチステージビルド、JRE 21)
├── demo-service/Dockerfile     # demo-service (PORT 環境変数対応)
├── Makefile
└── .github/workflows/ci.yml    # backend / demo-service / frontend を並列実行
```
