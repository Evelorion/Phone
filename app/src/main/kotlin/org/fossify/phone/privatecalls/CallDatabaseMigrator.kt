package org.fossify.phone.privatecalls

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * 把已有的明文通话库原地转成 SQLCipher 加密库。
 *
 * 和通讯录那边 DatabaseEncryptionMigrator 是同一套逻辑。之所以各自一份而不是
 * 抽成共享库，是因为这是两个独立的仓库、独立的 APK，共享代码意味着要么发一个
 * 公共 AAR，要么用 git subtree —— 对这个规模的项目都不划算。
 * 但两边的行为必须一致，改一边记得改另一边。
 */
object CallDatabaseMigrator {

    private const val TAG = "CallDbMigrator"

    class MigrationFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun encryptInPlace(context: Context, dbName: String, passphrase: ByteArray) {
        val plainFile = context.getDatabasePath(dbName)
        if (!plainFile.exists()) return
        if (isEncrypted(plainFile)) return

        val tempFile = File(plainFile.parentFile, "$dbName.encrypting")
        tempFile.delete()

        val passphraseText = String(passphrase, Charsets.US_ASCII)
        require(passphraseText.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            "通话库口令格式不对，拒绝拼进 SQL"
        }

        var plain: SQLiteDatabase? = null
        try {
            plain = SQLiteDatabase.openOrCreateDatabase(plainFile.absolutePath, "", null, null)
            val userVersion = plain.version

            plain.rawExecSQL("ATTACH DATABASE '${tempFile.absolutePath}' AS encrypted KEY '$passphraseText';")
            plain.rawExecSQL("SELECT sqlcipher_export('encrypted');")
            // Room 靠 user_version 判断 schema 版本。漏了这一步它会当成全新的空库，
            // 直接按 version 0 重建表 —— 通话记录就没了。
            plain.rawExecSQL("PRAGMA encrypted.user_version = $userVersion;")
            plain.rawExecSQL("DETACH DATABASE encrypted;")
            plain.close()
            plain = null

            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw MigrationFailed("导出后的加密文件是空的")
            }
            verifyOrThrow(tempFile, passphraseText, userVersion)

            File(plainFile.absolutePath + "-wal").delete()
            File(plainFile.absolutePath + "-shm").delete()
            if (!plainFile.delete()) throw MigrationFailed("删除明文通话库失败")
            if (!tempFile.renameTo(plainFile)) throw MigrationFailed("重命名加密通话库失败")

            Log.i(TAG, "$dbName 已转为加密存储")
        } catch (e: Exception) {
            tempFile.delete()
            throw if (e is MigrationFailed) e else MigrationFailed("加密迁移失败：${e.message}", e)
        } finally {
            runCatching { plain?.close() }
        }
    }

    /**
     * 能用空口令打开就说明是明文。
     * 不去读文件头 —— SQLCipher 默认把前 16 字节的盐也随机化了，没有稳定魔数可认。
     */
    private fun isEncrypted(file: File): Boolean {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openOrCreateDatabase(file.absolutePath, "", null, null)
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            false
        } catch (e: Exception) {
            true
        } finally {
            runCatching { db?.close() }
        }
    }

    private fun verifyOrThrow(file: File, passphrase: String, expectedVersion: Int) {
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openOrCreateDatabase(file.absolutePath, passphrase, null, null)
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getInt(0) == 0) {
                    throw MigrationFailed("加密后的库里一张表都没有")
                }
            }
            if (db.version != expectedVersion) {
                throw MigrationFailed("加密后的库 user_version 是 ${db.version}，应该是 $expectedVersion")
            }
        } finally {
            runCatching { db?.close() }
        }
    }
}
