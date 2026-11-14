package top.catnies.sparrow.cdkey.database;

import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 建立本服的五张 CDKEY 表. 时间使用 UTC epoch 毫秒, 玩家 UUID 以标准文本保存.
 * 定义和批次使用单调编号, 删除旧定义后可以继续安全地识别表单和生成码.
 */
final class CdkeySchema {

    private CdkeySchema() {
    }

    /**
     * 建立定义, 生成批次, 单码, 玩家用量和兑换历史, 并启用外键检查.
     *
     * @param database 插件独占的 SQLite 连接
     * @throws SQLException 无法设置连接或建立表时抛出
     */
    static void initialize(@NotNull Connection database) throws SQLException {
        try (Statement sql = database.createStatement()) {
            sql.execute("PRAGMA foreign_keys = ON");
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS cdkey_definitions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        kind TEXT NOT NULL CHECK (kind IN ('code', 'gift')),
                        identifier TEXT NOT NULL COLLATE BINARY,
                        commands_json TEXT NOT NULL,
                        starts_at INTEGER NOT NULL,
                        ends_at INTEGER NOT NULL CHECK (ends_at = -1 OR ends_at > starts_at),
                        total_limit INTEGER NOT NULL CHECK (total_limit >= -1),
                        player_limit INTEGER NOT NULL CHECK (player_limit >= -1),
                        used_count INTEGER NOT NULL DEFAULT 0 CHECK (used_count >= 0),
                        permission TEXT NOT NULL,
                        version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
                        creator_uuid TEXT NOT NULL,
                        creator_name TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        UNIQUE (kind, identifier),
                        CHECK ((kind = 'code' AND player_limit = 1) OR (kind = 'gift' AND total_limit = -1))
                    )
                    """);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS cdkey_batches (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        definition_id INTEGER NOT NULL REFERENCES cdkey_definitions (id),
                        quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 16),
                        creator_uuid TEXT NOT NULL,
                        creator_name TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        UNIQUE (id, definition_id)
                    )
                    """);
            // 联合外键将单码绑定到批次及其所属定义.
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS cdkey_keys (
                        batch_id INTEGER NOT NULL,
                        ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 16),
                        definition_id INTEGER NOT NULL,
                        code TEXT NOT NULL UNIQUE COLLATE BINARY,
                        redeemed_at INTEGER,
                        PRIMARY KEY (batch_id, ordinal),
                        FOREIGN KEY (batch_id, definition_id) REFERENCES cdkey_batches (id, definition_id)
                    )
                    """);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS cdkey_player_usage (
                        definition_id INTEGER NOT NULL REFERENCES cdkey_definitions (id),
                        player_uuid TEXT NOT NULL,
                        used_count INTEGER NOT NULL CHECK (used_count > 0),
                        PRIMARY KEY (definition_id, player_uuid)
                    )
                    """);
            // 人工礼包码的 token_code 为 NULL, 一次性码的完整码只能进入一次历史.
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS cdkey_redemptions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        definition_id INTEGER NOT NULL REFERENCES cdkey_definitions (id),
                        token_code TEXT UNIQUE REFERENCES cdkey_keys (code),
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        redeemed_at INTEGER NOT NULL,
                        snapshot_json TEXT NOT NULL
                    )
                    """);
            sql.execute("CREATE INDEX IF NOT EXISTS cdkey_definition_time ON cdkey_definitions (kind, created_at, id)");
            sql.execute("CREATE INDEX IF NOT EXISTS cdkey_batch_definition ON cdkey_batches (definition_id)");
            sql.execute("CREATE INDEX IF NOT EXISTS cdkey_key_definition ON cdkey_keys (definition_id, redeemed_at, batch_id DESC, ordinal)");
            sql.execute("CREATE INDEX IF NOT EXISTS cdkey_definition_history ON cdkey_redemptions (definition_id, redeemed_at, id)");
            sql.execute("CREATE INDEX IF NOT EXISTS cdkey_player_name ON cdkey_redemptions (player_name COLLATE NOCASE, player_uuid)");
            sql.execute("CREATE INDEX IF NOT EXISTS cdkey_player_history ON cdkey_redemptions (player_uuid, redeemed_at, id)");
        }
    }
}