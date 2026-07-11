## What

(変更点を 1-3 行で)

## Why

(動機。issue があれば `Closes #N`)

## How to test

- [ ] `make test` (全 gradle test) 緑
- [ ] MCP 統合テスト (INCIDENT_MCP_TEST_BIN 指定時のみ実行) 緑
- [ ] CI (gradle build + test) 緑

## チェックリスト

- [ ] Conventional Commits (subject 日本語、prefix 英語)
- [ ] 1 commit 1 関心事
- [ ] LLM Connector / MCP Provider 変更時は interface 抽象化を維持
- [ ] エンドポイント追加時は OpenAPI 互換性を意識
- [ ] README と矛盾しない
