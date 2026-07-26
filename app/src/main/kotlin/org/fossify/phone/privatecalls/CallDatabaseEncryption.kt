package org.fossify.phone.privatecalls

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import android.util.Log
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 私密通话库的加密。
 *
 * 和通讯录那边是同一套做法，但**必须是独立的一把 Keystore 密钥** ——
 * Android Keystore 是按 App 隔离的，电话 App 拿不到通讯录 App 的 Keystore 条目，
 * 反过来也一样。所以这里是一份结构相同、alias 不同的实现，不是复制粘贴的冗余。
 *
 * 需要说清楚的边界，和通讯录那边完全一样：
 * 这挡的是「拿到设备文件」的攻击者（adb backup、拆闪存、路径遍历漏洞）。
 * 设备被 root 且攻击者能注入本 App 进程时挡不住 —— App 自己得能开库，
 * 密钥就必须在 App 拿得到的地方。
 */
object CallDatabaseEncryption {

    private const val TAG = "CallDbEncryption"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "fc_phone_calldb_wrapper_v1"
    private const val PREFS = "fc_phone_calldb"
    private const val KEY_WRAPPED = "wrapped_passphrase"
    private const val KEY_REQUIRES_AUTH = "requires_auth"
    private val AAD = "fc.phone.calldb.v1".toByteArray(Charsets.UTF_8)
    private const val AUTH_VALIDITY_SECONDS = 300
    private const val GCM_IV_BYTES = 12

    @Volatile
    var lastError: String = ""
        private set

    /** 本次进程里通话库到底有没有跑在加密模式上。设置页应当显示这个。 */
    @Volatile
    var encrypted = false
        private set

    private var nativeLoaded = false

    /**
     * 返回给 Room 用的 SQLCipher 工厂。
     * 返回 null 表示加密不可用，调用方会退回明文并把原因显示出来。
     */
    @Synchronized
    fun openHelperFactory(context: Context): SupportSQLiteOpenHelper.Factory? {
        if (!loadNative()) return null

        val passphrase = try {
            getOrCreatePassphrase(context)
        } catch (e: UserNotAuthenticatedException) {
            lastError = "需要先通过屏幕锁验证才能打开通话记录"
            Log.i(TAG, lastError)
            return null
        } catch (e: Exception) {
            lastError = "无法取得通话库口令：${e.message}"
            Log.e(TAG, lastError, e)
            return null
        }

        return try {
            // 老版本可能留下一个明文库，第一次启用加密时原地转过来
            CallDatabaseMigrator.encryptInPlace(context, PrivateCallDatabase.DB_NAME, passphrase)
            val factory = SupportOpenHelperFactory(passphrase)
            encrypted = true
            lastError = ""
            factory
        } catch (e: Exception) {
            lastError = "通话库加密启用失败：${e.message}"
            encrypted = false
            Log.e(TAG, lastError, e)
            null
        }
    }

    fun requiresScreenLock(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUIRES_AUTH, false)

    /**
     * 切换是否需要屏幕锁验证。
     * 只是把同一个库口令重新包一次，数据库本身不用重新加密。
     *
     * 注意：打开之后，锁屏状态下来电时查不到私密通话历史，
     * 也就没法在通话界面显示「上次通话」之类的信息。
     */
    fun setRequireScreenLock(context: Context, require: Boolean) {
        val passphrase = loadPassphrase(context) ?: return
        storePassphrase(context, passphrase, require)
    }

    // ------------------------------------------------------------ 内部

    private fun loadNative(): Boolean {
        if (nativeLoaded) return true
        return try {
            System.loadLibrary("sqlcipher")
            nativeLoaded = true
            true
        } catch (e: Throwable) {
            lastError = "SQLCipher 原生库加载失败：${e.message}"
            Log.e(TAG, lastError, e)
            false
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getOrCreatePassphrase(context: Context): ByteArray {
        loadPassphrase(context)?.let { return it }
        // 64 位十六进制字符串。用 hex 而不是裸字节，是为了让迁移时的
        // ATTACH ... KEY '...' 语句和 SupportOpenHelperFactory 喂给
        // SQLCipher 的是同一个值，不会一边走 KDF 一边走裸密钥。
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = random.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
        random.fill(0)
        storePassphrase(context, hex, requiresScreenLock(context))
        return hex
    }

    private fun loadPassphrase(context: Context): ByteArray? {
        val stored = prefs(context).getString(KEY_WRAPPED, null)?.takeIf { it.isNotEmpty() } ?: return null
        val key = loadKey() ?: run {
            // Keystore 密钥没了（改了锁屏方式、恢复出厂设置……），
            // 已有的加密库永远打不开了。清掉标记，让上层重建一个空库。
            prefs(context).edit().clear().apply()
            return null
        }
        val blob = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() ?: return null
        if (blob.size <= GCM_IV_BYTES) return null
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, GCM_IV_BYTES)))
                updateAAD(AAD)
                doFinal(blob.copyOfRange(GCM_IV_BYTES, blob.size))
            }
        } catch (e: UserNotAuthenticatedException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun storePassphrase(context: Context, passphrase: ByteArray, requireScreenLock: Boolean) {
        if (requireScreenLock != requiresScreenLock(context)) deleteKey()
        val key = loadKey() ?: generateKey(requireScreenLock, useStrongBox = true)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val blob = cipher.iv + cipher.doFinal(passphrase)
        prefs(context).edit()
            .putString(KEY_WRAPPED, Base64.encodeToString(blob, Base64.NO_WRAP))
            .putBoolean(KEY_REQUIRES_AUTH, requireScreenLock)
            .apply()
    }

    private fun loadKey(): SecretKey? = runCatching {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(KEY_ALIAS, null) as? SecretKey
    }.getOrNull()

    private fun deleteKey() {
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    private fun generateKey(requireScreenLock: Boolean, useStrongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).apply {
            setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            setKeySize(256)
            setRandomizedEncryptionRequired(true)
            if (requireScreenLock) {
                setUserAuthenticationRequired(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        AUTH_VALIDITY_SECONDS,
                        KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
                }
            }
            if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setIsStrongBoxBacked(true)
            }
        }.build()

        return try {
            generator.init(spec)
            generator.generateKey()
        } catch (e: Exception) {
            // StrongBoxUnavailableException 在 API 28 才有，按类型 catch 会在低版本
            // 触发类加载问题，所以按名字判断后降级重试一次。
            if (useStrongBox && e::class.java.simpleName == "StrongBoxUnavailableException") {
                deleteKey()
                generateKey(requireScreenLock, useStrongBox = false)
            } else {
                throw e
            }
        }
    }
}
