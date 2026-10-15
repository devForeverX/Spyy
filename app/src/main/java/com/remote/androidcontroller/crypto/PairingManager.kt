package com.remote.androidcontroller.crypto

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class PairedDevice(
    val pairingId: String,
    val peerPublicKeyBase64: String,
    val peerDeviceName: String,
    val sharedSecretBase64: String,
    val pairedAtTimestamp: Long
)

/**
 * Pure cryptographic functions for ECDH key agreement, HKDF key derivation,
 * SAS (Short Authentication String) generation, and authentication proofs.
 * Can be verified in standard JVM unit tests without Android runtime dependencies.
 */
object PairingCrypto {
    private val secureRandom = SecureRandom()

    fun generatePairingCode(): String {
        val number = 100000 + secureRandom.nextInt(900000)
        return number.toString()
    }

    fun generateNonce(): ByteArray {
        val nonce = ByteArray(16)
        secureRandom.nextBytes(nonce)
        return nonce
    }

    fun generateEphemeralEcdhKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    fun computeSharedSecret(myPrivateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(myPrivateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        return keyAgreement.generateSecret()
    }

    fun deriveHandshakeKeys(
        sharedSecret: ByteArray,
        nonceA: ByteArray,
        nonceB: ByteArray,
        pairingCode: String
    ): Pair<ByteArray, ByteArray> {
        val saltInput = ByteBuffer.allocate(nonceA.size + nonceB.size + pairingCode.toByteArray().size)
            .put(nonceA)
            .put(nonceB)
            .put(pairingCode.toByteArray())
            .array()

        val salt = MessageDigest.getInstance("SHA-256").digest(saltInput)

        // HKDF-Extract
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(sharedSecret)

        // RFC 5869 HKDF-Expand: derive one 64-byte output and split into independent keys.
        val info = "AndroidRemotePairing_v2".toByteArray(Charsets.UTF_8)
        val outputKeyMaterial = try {
            hkdfExpand(prk, info, 64)
        } finally {
            prk.fill(0)
        }
        val authKey = outputKeyMaterial.copyOfRange(0, 32)
        val confirmKey = outputKeyMaterial.copyOfRange(32, 64)
        outputKeyMaterial.fill(0)

        return Pair(authKey, confirmKey)
    }

    /** RFC 5869 HKDF-Expand (SHA-256), with T(n-1) || info || n per block. */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..(255 * 32)) { "HKDF output length is out of range" }
        if (length == 0) return byteArrayOf()

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val output = ByteArray(length)
        var previousBlock = byteArrayOf()
        var offset = 0
        var blockCounter = 1
        while (offset < length) {
            mac.reset()
            if (previousBlock.isNotEmpty()) mac.update(previousBlock)
            mac.update(info)
            mac.update(blockCounter.toByte())
            previousBlock = mac.doFinal()
            val copyLength = minOf(previousBlock.size, length - offset)
            previousBlock.copyInto(output, destinationOffset = offset, startIndex = 0, endIndex = copyLength)
            offset += copyLength
            blockCounter++
        }
        previousBlock.fill(0)
        return output
    }

    fun computeAuthProof(
        authKey: ByteArray,
        label: String,
        myPub: ByteArray,
        peerPub: ByteArray,
        nonceA: ByteArray,
        nonceB: ByteArray
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(authKey, "HmacSHA256"))
        mac.update(label.toByteArray())
        mac.update(myPub)
        mac.update(peerPub)
        mac.update(nonceA)
        mac.update(nonceB)
        return mac.doFinal()
    }

    fun calculateSasCode(confirmKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(confirmKey)
        val num = ByteBuffer.wrap(hash).int and 0x7FFFFFFF
        val sas = (num % 1000000).toString().padStart(6, '0')
        return "${sas.substring(0, 3)}-${sas.substring(3, 6)}"
    }

    fun computePairingId(pubKeyA: String, pubKeyB: String): String {
        val (first, second) = if (pubKeyA < pubKeyB) pubKeyA to pubKeyB else pubKeyB to pubKeyA
        val combined = "$first:$second:AndroidRemote_v1"
        val hash = MessageDigest.getInstance("SHA-256").digest(combined.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    fun decodePublicKey(base64: String): PublicKey {
        val bytes = Base64.getDecoder().decode(base64)
        val spec = X509EncodedKeySpec(bytes)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    fun encodePublicKey(publicKey: PublicKey): String {
        return Base64.getEncoder().encodeToString(publicKey.encoded)
    }
}

class PairingManager(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val IDENTITY_KEY_ALIAS = "com.remote.identity_ec_key"
        private const val SECURE_PEER_FILE = "paired_peer_secure.bin"
        private const val MAX_PAIRING_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 15 * 60 * 1000L // 15 Minutes

        private var failedAttempts = 0
        private var lockoutUntilTimestamp = 0L
    }

    private val keystoreManager = KeystoreManager.getInstance()
    private val prefs = context.getSharedPreferences("com.remote.identity_prefs", Context.MODE_PRIVATE)

    // Software fallback EC keypair if AndroidKeyStore hardware engine is missing or fails
    private val fallbackKeyPair: KeyPair by lazy {
        loadOrGenerateFallbackKeyPair()
    }

    init {
        ensureIdentityKeyPairExists()
    }

    private fun loadOrGenerateFallbackKeyPair(): KeyPair {
        val pubBase64 = prefs.getString("fallback_pub_key", null)
        val privBase64 = prefs.getString("fallback_priv_key", null)
        if (!pubBase64.isNullOrEmpty() && !privBase64.isNullOrEmpty()) {
            val loaded = runCatching {
                val kf = java.security.KeyFactory.getInstance("EC")
                val pubSpec = java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(pubBase64))
                val privSpec = java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(privBase64))
                KeyPair(kf.generatePublic(pubSpec), kf.generatePrivate(privSpec))
            }.getOrNull()
            if (loaded != null) return loaded
        }
        val generated = PairingCrypto.generateEphemeralEcdhKeyPair()
        runCatching {
            prefs.edit()
                .putString("fallback_pub_key", java.util.Base64.getEncoder().encodeToString(generated.public.encoded))
                .putString("fallback_priv_key", java.util.Base64.getEncoder().encodeToString(generated.private.encoded))
                .apply()
        }
        return generated
    }

    private fun ensureIdentityKeyPairExists() {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(IDENTITY_KEY_ALIAS)) {
                val kpg = KeyPairGenerator.getInstance("EC", ANDROID_KEYSTORE)
                val parameterSpec = android.security.keystore.KeyGenParameterSpec.Builder(
                    IDENTITY_KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN or
                            android.security.keystore.KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                    .build()

                kpg.initialize(parameterSpec)
                kpg.generateKeyPair()
            }
        }
    }

    fun getPublicIdentityKey(): PublicKey {
        val keyFromStore = runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.getCertificate(IDENTITY_KEY_ALIAS)?.publicKey
        }.getOrNull()
        return keyFromStore ?: fallbackKeyPair.public
    }

    fun getPublicIdentityKeyBase64(): String {
        return PairingCrypto.encodePublicKey(getPublicIdentityKey())
    }

    /** Signs a fresh server challenge with the non-exportable Android Keystore identity key or fallback key. */
    fun signIdentityChallenge(challenge: ByteArray): ByteArray {
        val validChallenge = if (challenge.size in 16..128) challenge else challenge.copyOf(32)
        val privateKey = runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.getKey(IDENTITY_KEY_ALIAS, null) as? PrivateKey
        }.getOrNull() ?: fallbackKeyPair.private

        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(validChallenge)
            sign()
        }
    }

    fun generatePairingCode(): String = PairingCrypto.generatePairingCode()
    fun generateNonce(): ByteArray = PairingCrypto.generateNonce()
    fun generateEphemeralEcdhKeyPair(): KeyPair = PairingCrypto.generateEphemeralEcdhKeyPair()
    fun computeSharedSecret(myPrivateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray =
        PairingCrypto.computeSharedSecret(myPrivateKey, peerPublicKey)
    fun deriveHandshakeKeys(sharedSecret: ByteArray, nonceA: ByteArray, nonceB: ByteArray, pairingCode: String) =
        PairingCrypto.deriveHandshakeKeys(sharedSecret, nonceA, nonceB, pairingCode)
    fun computeAuthProof(authKey: ByteArray, label: String, myPub: ByteArray, peerPub: ByteArray, nonceA: ByteArray, nonceB: ByteArray) =
        PairingCrypto.computeAuthProof(authKey, label, myPub, peerPub, nonceA, nonceB)
    fun calculateSasCode(confirmKey: ByteArray): String = PairingCrypto.calculateSasCode(confirmKey)
    fun computePairingId(pubKeyA: String, pubKeyB: String): String = PairingCrypto.computePairingId(pubKeyA, pubKeyB)
    fun decodePublicKey(base64: String): PublicKey = PairingCrypto.decodePublicKey(base64)

    fun checkRateLimit(): Boolean {
        if (System.currentTimeMillis() < lockoutUntilTimestamp) {
            return false
        }
        return true
    }

    fun recordFailedAttempt() {
        failedAttempts++
        if (failedAttempts >= MAX_PAIRING_ATTEMPTS) {
            lockoutUntilTimestamp = System.currentTimeMillis() + LOCKOUT_DURATION_MS
            failedAttempts = 0
        }
    }

    fun recordSuccess() {
        failedAttempts = 0
        lockoutUntilTimestamp = 0L
    }

    fun getRemainingLockoutSeconds(): Long {
        val diff = lockoutUntilTimestamp - System.currentTimeMillis()
        return if (diff > 0) diff / 1000 else 0
    }

    fun savePairedDevice(device: PairedDevice) {
        runCatching {
            val raw = "${device.pairingId}\n${device.peerPublicKeyBase64}\n${device.peerDeviceName}\n${device.sharedSecretBase64}\n${device.pairedAtTimestamp}"
            val encrypted = keystoreManager.encrypt(raw.toByteArray(Charsets.UTF_8))
            val file = File(context.filesDir, SECURE_PEER_FILE)
            file.writeBytes(encrypted)
        }
    }

    fun getPairedDevice(): PairedDevice? {
        val file = File(context.filesDir, SECURE_PEER_FILE)
        if (!file.exists()) return null
        return try {
            val encrypted = file.readBytes()
            val decrypted = String(keystoreManager.decrypt(encrypted), Charsets.UTF_8)
            val parts = decrypted.split("\n")
            if (parts.size >= 5) {
                PairedDevice(
                    pairingId = parts[0],
                    peerPublicKeyBase64 = parts[1],
                    peerDeviceName = parts[2],
                    sharedSecretBase64 = parts[3],
                    pairedAtTimestamp = parts[4].toLongOrNull() ?: System.currentTimeMillis()
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun revokePairing() {
        val file = File(context.filesDir, SECURE_PEER_FILE)
        if (file.exists()) {
            file.delete()
        }
    }
}
