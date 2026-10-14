package com.remote.androidcontroller

import com.remote.androidcontroller.crypto.PairingCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PairingHandshakeTest {

    @Test
    fun testRealEcdhKeyAgreementAndMutualSharedSecret() {
        // Alice generates EC keypair
        val aliceKeyPair = PairingCrypto.generateEphemeralEcdhKeyPair()
        assertNotNull(aliceKeyPair.public)
        assertNotNull(aliceKeyPair.private)

        // Bob generates EC keypair
        val bobKeyPair = PairingCrypto.generateEphemeralEcdhKeyPair()
        assertNotNull(bobKeyPair.public)
        assertNotNull(bobKeyPair.private)

        // Alice computes shared secret using Bob's public key
        val aliceSharedSecret = PairingCrypto.computeSharedSecret(aliceKeyPair.private, bobKeyPair.public)

        // Bob computes shared secret using Alice's public key
        val bobSharedSecret = PairingCrypto.computeSharedSecret(bobKeyPair.private, aliceKeyPair.public)

        // Both shared secrets MUST be non-empty and identical
        assertEquals(32, aliceSharedSecret.size)
        assertTrue(aliceSharedSecret.contentEquals(bobSharedSecret))
    }

    @Test
    fun testHkdfKeyDerivationAndSasVerification() {
        val aliceKeyPair = PairingCrypto.generateEphemeralEcdhKeyPair()
        val bobKeyPair = PairingCrypto.generateEphemeralEcdhKeyPair()

        val sharedSecret = PairingCrypto.computeSharedSecret(aliceKeyPair.private, bobKeyPair.public)
        val nonceA = PairingCrypto.generateNonce()
        val nonceB = PairingCrypto.generateNonce()
        val pairingCode = PairingCrypto.generatePairingCode()

        // Both parties derive handshake keys using identical nonces and pairing code
        val (aliceAuthKey, aliceConfirmKey) = PairingCrypto.deriveHandshakeKeys(
            sharedSecret = sharedSecret,
            nonceA = nonceA,
            nonceB = nonceB,
            pairingCode = pairingCode
        )

        val (bobAuthKey, bobConfirmKey) = PairingCrypto.deriveHandshakeKeys(
            sharedSecret = sharedSecret,
            nonceA = nonceA,
            nonceB = nonceB,
            pairingCode = pairingCode
        )

        // Auth and Confirm keys must be identical on both sides
        assertTrue(aliceAuthKey.contentEquals(bobAuthKey))
        assertTrue(aliceConfirmKey.contentEquals(bobConfirmKey))

        // Both devices must calculate the EXACT same SAS code
        val aliceSas = PairingCrypto.calculateSasCode(aliceConfirmKey)
        val bobSas = PairingCrypto.calculateSasCode(bobConfirmKey)

        assertEquals(aliceSas, bobSas)
        assertEquals(7, aliceSas.length)
        assertTrue(aliceSas.contains("-"))

        // Derivation with wrong pairing code MUST produce a completely different SAS code
        val (_, wrongConfirmKey) = PairingCrypto.deriveHandshakeKeys(
            sharedSecret = sharedSecret,
            nonceA = nonceA,
            nonceB = nonceB,
            pairingCode = if (pairingCode.last() == '9') pairingCode.dropLast(1) + "8" else pairingCode.dropLast(1) + "9"
        )
        val wrongSas = PairingCrypto.calculateSasCode(wrongConfirmKey)
        assertNotEquals(aliceSas, wrongSas)
    }

    @Test
    fun testAuthenticationProofVerification() {
        val aliceKeyPair = PairingCrypto.generateEphemeralEcdhKeyPair()
        val bobKeyPair = PairingCrypto.generateEphemeralEcdhKeyPair()
        val sharedSecret = PairingCrypto.computeSharedSecret(aliceKeyPair.private, bobKeyPair.public)

        val nonceA = PairingCrypto.generateNonce()
        val nonceB = PairingCrypto.generateNonce()
        val pairingCode = PairingCrypto.generatePairingCode()

        val (authKey, _) = PairingCrypto.deriveHandshakeKeys(sharedSecret, nonceA, nonceB, pairingCode)

        val alicePubBytes = aliceKeyPair.public.encoded
        val bobPubBytes = bobKeyPair.public.encoded

        // Alice computes proof tag for Bob
        val proofFromAlice = PairingCrypto.computeAuthProof(
            authKey = authKey,
            label = "ClientProof",
            myPub = alicePubBytes,
            peerPub = bobPubBytes,
            nonceA = nonceA,
            nonceB = nonceB
        )

        // Bob computes expected proof tag from Alice
        val expectedProof = PairingCrypto.computeAuthProof(
            authKey = authKey,
            label = "ClientProof",
            myPub = alicePubBytes,
            peerPub = bobPubBytes,
            nonceA = nonceA,
            nonceB = nonceB
        )

        assertTrue(proofFromAlice.contentEquals(expectedProof))

        // Tampered proof MUST NOT match
        val tamperedProof = proofFromAlice.copyOf()
        tamperedProof[0] = (tamperedProof[0] + 1).toByte()
        assertFalse(tamperedProof.contentEquals(expectedProof))
    }

    @Test
    fun testHkdfExpandMatchesRfc5869TestCase1() {
        // RFC 5869 Appendix A.1: prove HKDF-Expand is standards-compliant, not merely symmetric.
        val ikm = ByteArray(22) { 0x0b.toByte() }
        val salt = hexToBytes("000102030405060708090a0b0c")
        val info = hexToBytes("f0f1f2f3f4f5f6f7f8f9")
        val extract = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(salt, "HmacSHA256"))
        }.doFinal(ikm)
        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            bytesToHex(extract)
        )
        val output = PairingCrypto.hkdfExpand(extract, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            bytesToHex(output)
        )
    }

    private fun hexToBytes(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun bytesToHex(value: ByteArray): String = value.joinToString("") { "%02x".format(it) }

    @Test
    fun testCanonicalPairingId() {
        val keyA = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEKeyA111"
        val keyB = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEKeyB222"

        val id1 = PairingCrypto.computePairingId(keyA, keyB)
        val id2 = PairingCrypto.computePairingId(keyB, keyA)

        // Must be canonically ordered and produce identical pairing ID
        assertEquals(id1, id2)
        assertTrue(id1.isNotBlank())
    }
}
