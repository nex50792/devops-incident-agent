package com.example.incident.postmortem

import com.example.incident.tools.SearchPostmortemTool
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * アプリ起動時に schema.sql を流し込み、seed 済みでなければ postmortem サンプルを
 * embedding とともに INSERT する。
 *
 * embedding 生成には Gemini `gemini-embedding-001` を使う (無料枠内 1M tokens/日)。
 */
@Component
@ConditionalOnProperty(name = ["spring.datasource.url"])
class PostmortemSeeder(
    private val jdbc: JdbcTemplate,
    private val tool: SearchPostmortemTool,
) : CommandLineRunner {
    private val log = LoggerFactory.getLogger(PostmortemSeeder::class.java)

    override fun run(vararg args: String) {
        runCatching { runSchema() }
            .onFailure { log.warn("schema.sql の適用に失敗 (RDS 未接続 or 権限不足の可能性): {}", it.message) }

        val count =
            runCatching { jdbc.queryForObject("SELECT COUNT(*) FROM postmortems", Int::class.java) ?: 0 }
                .getOrElse {
                    log.warn("postmortems SELECT 失敗、seed をスキップ: {}", it.message)
                    return
                }

        if (count >= SAMPLES.size) {
            log.info("postmortems seed 済み (count={}) スキップ", count)
            return
        }

        log.info("postmortems seed 開始 (existing={}, target={})", count, SAMPLES.size)
        for (sample in SAMPLES) {
            runCatching { insertSample(sample) }
                .onFailure { log.warn("seed insert failed id={}: {}", sample.id, it.message) }
        }
        log.info("postmortems seed 完了")
    }

    private fun runSchema() {
        val stream =
            javaClass.classLoader.getResourceAsStream("db/schema.sql")
                ?: error("db/schema.sql not found in classpath")
        val sql = stream.bufferedReader().use { it.readText() }
        // 単純に ; で分割して逐次実行
        sql
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { statement -> jdbc.execute(statement) }
    }

    private fun insertSample(sample: PostmortemSample) {
        val existing = jdbc.queryForList("SELECT 1 FROM postmortems WHERE id = ?", sample.id)
        if (existing.isNotEmpty()) return
        val text = "${sample.title}\n${sample.summary}"
        val embedding = tool.embed(text) ?: error("embedding generation failed for ${sample.id}")
        val vector = embedding.joinToString(",", prefix = "[", postfix = "]")
        jdbc.update(
            "INSERT INTO postmortems (id, title, summary, embedding) VALUES (?, ?, ?, ?::vector)",
            sample.id,
            sample.title,
            sample.summary,
            vector,
        )
    }

    private data class PostmortemSample(
        val id: String,
        val title: String,
        val summary: String,
    )

    private companion object {
        val SAMPLES =
            listOf(
                PostmortemSample(
                    id = "pm-2026-01",
                    title = "auth-api の DB connection pool 枯渇 (2026-05)",
                    summary =
                        """
                        auth-api で 5xx が急増。原因は DB pool size 20 に対して burst 50 が到来、
                        Connection close 漏れ (finally block 抜け) で pool が枯渇。
                        暫定: pool を 30 に拡大 + 監視強化。恒久: @Transactional の close 保証を review。
                        """.trimIndent(),
                ),
                PostmortemSample(
                    id = "pm-2026-02",
                    title = "checkout-api の DB deadlock (2026-04)",
                    summary =
                        """
                        checkout-api で書き込み時 deadlock。原因は 2 トランザクションが同じ行を
                        逆順にロック取得して衝突。UPDATE 順を固定 + retry with backoff で解消。
                        """.trimIndent(),
                ),
                PostmortemSample(
                    id = "pm-2026-03",
                    title = "orders-api の 外部 payment API 500 連鎖 (2026-03)",
                    summary =
                        """
                        orders-api が外部の payment provider の 500 を素通し、全 order が失敗。
                        Circuit breaker + fallback (待機列に載せて後追い処理) を導入して mitigate。
                        """.trimIndent(),
                ),
                PostmortemSample(
                    id = "pm-2026-04",
                    title = "auth-api の JWT 検証遅延 (2026-02)",
                    summary =
                        """
                        auth-api の JWKS fetch が bootstrap 時のみで、rotation を反映しない構成。
                        週 1 の key rotation 時に全リクエスト 401、5 分で復旧するが影響大。
                        JWKS を 60 秒 cache で refresh するよう修正。
                        """.trimIndent(),
                ),
                PostmortemSample(
                    id = "pm-2026-05",
                    title = "checkout-api の slow query (2026-01)",
                    summary =
                        """
                        checkout-api の p99 latency が閾値超え。ORDER BY created_at DESC の index が
                        効かず full scan に。covering index (created_at, id) 追加で復旧。
                        """.trimIndent(),
                ),
            )
    }
}
