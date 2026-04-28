package com.meshchat.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Android Keystore-backed EC P-256 identity.
 *
 * The private key is generated once, stored in the hardware-backed Keystore,
 * and never exported. The public key is exported as base64(X.509 DER) and
 * used as the stable node identity for routing and message attribution.
 *
 * Fingerprint: first 8 bytes of SHA-256(public key DER) displayed as 16 hex chars.
 */
class CryptoManager {

    companion object {
        private const val KEY_ALIAS = "meshchat_identity_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }

    private val keyPair: KeyPair = loadOrGenerateKeyPair()

    val publicKeyBase64: String = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    private fun loadOrGenerateKeyPair(): KeyPair {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (entry != null) return KeyPair(entry.certificate.publicKey, entry.privateKey)
        }
        val keyGen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
        keyGen.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setKeySize(256)
                .build()
        )
        return keyGen.generateKeyPair()
    }

    fun sign(data: ByteArray): String {
        val sig = Signature.getInstance(SIGNATURE_ALGORITHM)
        sig.initSign(keyPair.private)
        sig.update(data)
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    fun verify(peerPublicKeyBase64: String, data: ByteArray, signatureBase64: String): Boolean {
        return try {
            val pubKey = decodePublicKey(peerPublicKeyBase64)
            val sig = Signature.getInstance(SIGNATURE_ALGORITHM)
            sig.initVerify(pubKey)
            sig.update(data)
            sig.verify(Base64.getDecoder().decode(signatureBase64))
        } catch (_: Exception) {
            false
        }
    }

    fun fingerprint(publicKeyBase64: String): String {
        return try {
            val bytes = Base64.getDecoder().decode(publicKeyBase64)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            digest.take(8).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    // ── Typed signing helpers ─────────────────────────────────────────────────

    fun signRoutedMessage(
        packetId: String,
        sourcePublicKey: String,
        sourceDisplayName: String,
        destinationPublicKey: String,
        destinationDisplayName: String,
        timestamp: Long,
        body: String
    ): String = sign(
        routedMessageBytes(
            packetId,
            sourcePublicKey,
            sourceDisplayName,
            destinationPublicKey,
            destinationDisplayName,
            timestamp,
            body
        )
    )

    fun verifyRoutedMessage(
        peerPublicKeyBase64: String,
        packetId: String,
        sourcePublicKey: String,
        sourceDisplayName: String,
        destinationPublicKey: String,
        destinationDisplayName: String,
        timestamp: Long,
        body: String,
        signatureBase64: String
    ): Boolean = verify(
        peerPublicKeyBase64,
        routedMessageBytes(
            packetId,
            sourcePublicKey,
            sourceDisplayName,
            destinationPublicKey,
            destinationDisplayName,
            timestamp,
            body
        ),
        signatureBase64
    )

    fun signBeacon(
        nodeId: String, displayName: String, timestamp: Long, seqNum: Int,
        relayCapable: Boolean = false, geohash: String = "", lat: Double = 0.0, lon: Double = 0.0
    ): String = sign(beaconBytes(nodeId, displayName, timestamp, seqNum, relayCapable, geohash, lat, lon))

    fun verifyBeacon(
        peerPublicKeyBase64: String, nodeId: String, displayName: String,
        timestamp: Long, seqNum: Int, relayCapable: Boolean = false, geohash: String = "",
        lat: Double = 0.0, lon: Double = 0.0, signatureBase64: String
    ): Boolean = verify(
        peerPublicKeyBase64,
        beaconBytes(nodeId, displayName, timestamp, seqNum, relayCapable, geohash, lat, lon),
        signatureBase64
    )

    fun signDeliveryAck(packetId: String, destinationNodeId: String, timestamp: Long): String =
        sign(deliveryAckBytes(packetId, destinationNodeId, timestamp))

    fun verifyDeliveryAck(
        peerPublicKeyBase64: String, packetId: String,
        destinationNodeId: String, timestamp: Long, signatureBase64: String
    ): Boolean = verify(peerPublicKeyBase64, deliveryAckBytes(packetId, destinationNodeId, timestamp), signatureBase64)

    fun signRouteFailure(packetId: String, failingNodeId: String, reasonId: Byte, timestamp: Long): String =
        sign(routeFailureBytes(packetId, failingNodeId, reasonId, timestamp))

    fun verifyRouteFailure(
        peerPublicKeyBase64: String, packetId: String, failingNodeId: String,
        reasonId: Byte, timestamp: Long, signatureBase64: String
    ): Boolean = verify(peerPublicKeyBase64, routeFailureBytes(packetId, failingNodeId, reasonId, timestamp), signatureBase64)

    // ── Canonical byte sequences (what gets signed) ───────────────────────────

    private fun routedMessageBytes(
        packetId: String,
        srcKey: String,
        srcDisplayName: String,
        dstKey: String,
        dstDisplayName: String,
        timestamp: Long,
        body: String
    ): ByteArray = ByteArrayOutputStream().run {
        write(packetId.toByteArray())
        write(srcKey.toByteArray())
        write(srcDisplayName.toByteArray())
        write(dstKey.toByteArray())
        write(dstDisplayName.toByteArray())
        writeLongBE(timestamp)
        write(body.toByteArray())
        toByteArray()
    }

    private fun beaconBytes(
        nodeId: String, displayName: String, timestamp: Long, seqNum: Int,
        relayCapable: Boolean, geohash: String, lat: Double, lon: Double
    ): ByteArray = ByteArrayOutputStream().run {
        write(nodeId.toByteArray())
        write(displayName.toByteArray())
        writeLongBE(timestamp)
        writeIntBE(seqNum)
        write(if (relayCapable) 1 else 0)
        write(geohash.toByteArray())
        writeLongBE(java.lang.Double.doubleToRawLongBits(lat))
        writeLongBE(java.lang.Double.doubleToRawLongBits(lon))
        toByteArray()
    }

    private fun deliveryAckBytes(packetId: String, dstNodeId: String, timestamp: Long): ByteArray =
        ByteArrayOutputStream().run {
            write(packetId.toByteArray())
            write(dstNodeId.toByteArray())
            writeLongBE(timestamp)
            toByteArray()
        }

    private fun routeFailureBytes(packetId: String, failingNodeId: String, reasonId: Byte, timestamp: Long): ByteArray =
        ByteArrayOutputStream().run {
            write(packetId.toByteArray())
            write(failingNodeId.toByteArray())
            write(reasonId.toInt())
            writeLongBE(timestamp)
            toByteArray()
        }

    private fun ByteArrayOutputStream.writeLongBE(v: Long) {
        for (shift in 56 downTo 0 step 8) write(((v shr shift) and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.writeIntBE(v: Int) {
        write((v shr 24) and 0xFF); write((v shr 16) and 0xFF)
        write((v shr 8) and 0xFF); write(v and 0xFF)
    }

    private fun decodePublicKey(base64: String): PublicKey =
        KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC)
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))
}
