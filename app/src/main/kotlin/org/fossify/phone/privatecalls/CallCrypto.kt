package org.fossify.phone.privatecalls

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 通话记录的加密原语。
 *
 * ⚠ 这个文件和通讯录 App 里的 `sync/crypto/Crypto.kt` 必须逐字节等价。
 * 两个仓库各留一份是因为它们是独立的 APK，共享代码要么发公共 AAR、
 * 要么 git subtree，对这个规模的项目都不划算。代价是改一边必须改另一边，
 * 而且两边的 CryptoVectorsTest 用的是同一组期望值 ——
 * 只要有一条对不上，两个 App 之间、以及和服务器之间的数据就解不开了。
 *
 * 期望值来源：server/test/vectors.expected.json
 */
object CallCrypto {

    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val KEY_BYTES = 32
    const val PAD_BLOCK = 256
    const val SCHEMA_VERSION = 1

    private const val INFO_RECORD = "fc.rec.v1"

    private val secureRandom = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

    /** RFC 5869 HKDF-SHA256。只需要 ≤32 字节输出，所以 expand 只跑一轮。 */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray {
        require(length in 1..32) { "这里的 HKDF 只支持最多 32 字节输出" }
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = extract.doFinal(ikm)

        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        expand.update(info.toByteArray(Charsets.UTF_8))
        expand.update(0x01)
        val okm = expand.doFinal()
        prk.fill(0)
        return okm.copyOf(length)
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** 返回 nonce ‖ ciphertext ‖ tag。 */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "密钥必须是 32 字节" }
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "密钥必须是 32 字节" }
        require(sealed.size >= NONCE_BYTES + TAG_BYTES) { "密文过短" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BYTES * 8, sealed.copyOfRange(0, NONCE_BYTES)),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed.copyOfRange(NONCE_BYTES, sealed.size))
    }

    /** ISO/IEC 7816-4：补一个 0x80 再补 0x00 到 PAD_BLOCK 整数倍，抹平记录长度差异。 */
    fun pad(data: ByteArray): ByteArray {
        val total = (data.size / PAD_BLOCK + 1) * PAD_BLOCK
        return ByteArray(total).also {
            data.copyInto(it)
            it[data.size] = 0x80.toByte()
        }
    }

    fun unpad(data: ByteArray): ByteArray {
        for (i in data.indices.reversed()) {
            when (data[i]) {
                0x80.toByte() -> return data.copyOfRange(0, i)
                0x00.toByte() -> continue
                else -> throw IllegalArgumentException("填充格式错误")
            }
        }
        throw IllegalArgumentException("填充格式错误")
    }

    // ------------------------------------------------------------ 记录

    fun deriveRecordKey(collectionKey: ByteArray, uuid: String): ByteArray =
        hkdf(collectionKey, uuidToBytes(uuid), INFO_RECORD)

    /**
     * AAD 绑定 uuid + rev + schema。
     * 恶意服务器没法把旧密文冒充成新版本推回来，也没法把 A 的记录塞到 B 的位置上。
     */
    fun recordAad(uuid: String, rev: Int, schemaVersion: Int = SCHEMA_VERSION): ByteArray {
        val out = ByteArray(21)
        uuidToBytes(uuid).copyInto(out, 0)
        out[16] = (rev ushr 24).toByte()
        out[17] = (rev ushr 16).toByte()
        out[18] = (rev ushr 8).toByte()
        out[19] = rev.toByte()
        out[20] = schemaVersion.toByte()
        return out
    }

    fun encryptRecord(collectionKey: ByteArray, uuid: String, rev: Int, json: String): ByteArray {
        val key = deriveRecordKey(collectionKey, uuid)
        try {
            return seal(key, pad(json.toByteArray(Charsets.UTF_8)), recordAad(uuid, rev))
        } finally {
            key.fill(0)
        }
    }

    fun decryptRecord(collectionKey: ByteArray, uuid: String, rev: Int, sealed: ByteArray): String {
        val key = deriveRecordKey(collectionKey, uuid)
        try {
            return unpad(open(key, sealed, recordAad(uuid, rev))).toString(Charsets.UTF_8)
        } finally {
            key.fill(0)
        }
    }

    // ------------------------------------------------------------ 工具

    fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append("0123456789abcdef"[v ushr 4])
            out.append("0123456789abcdef"[v and 0x0f])
        }
        return out.toString()
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "十六进制字符串长度必须是偶数" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    fun uuidToBytes(uuid: String): ByteArray = fromHex(uuid.replace("-", ""))

    /**
     * 由内容确定性推导出 UUID 格式的字符串。
     *
     * 同一通电话可能被 ContentObserver 和通话结束的兜底扫描各抓一次，
     * 用确定性 id 天然去重。服务端要求 uuid 是 36 字符带连字符的格式，
     * 所以这里把哈希的前 16 字节排成 UUID 的样子（不是真正的 v4，但格式合法）。
     */
    fun deterministicUuid(identity: String): String {
        val h = sha256(identity.toByteArray(Charsets.UTF_8))
        val hex = toHex(h.copyOf(16))
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    fun wipe(vararg arrays: ByteArray?) {
        for (a in arrays) a?.fill(0)
    }
}
