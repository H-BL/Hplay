package com.hpplay.mfi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Guards the MFi credential that ships inside the APK under `assets/offline-mfi`.
 *
 * The bytes are the ones the reference build bundled; this test fails loudly if they are replaced
 * with something that is not a usable Apple-signed AuthV3 identity.
 */
class BuiltInMfiCredentialTest {
    @Test
    fun bundledCredentialLoadsAsAuthV3() {
        val directory = findAssetDirectory()
        assumeTrue("bundled MFi assets are not on the test working directory", directory != null)

        val certificate = File(directory, "certificate.p7b").readBytes()
        val privateKey = File(directory, "identity.pk8").readBytes()

        val client = LocalMfiAuthenticationClient.fromBytes(certificate, privateKey)

        assertEquals(MfiCertificateType.MFI, client.certificateType)
        assertEquals(3, client.protocolMajor())
        assertTrue(client.readCertificate(65_525).contentEquals(certificate))

        // AuthV3 signs a 32-byte challenge into a fixed 64-byte raw ECDSA signature.
        val signature = client.signChallenge(ByteArray(32) { it.toByte() })
        assertEquals(64, signature.size)
    }

    private fun findAssetDirectory(): File? = ASSET_CANDIDATES
        .map(::File)
        .firstOrNull { it.isDirectory }

    private companion object {
        val ASSET_CANDIDATES = listOf(
            "src/main/assets/offline-mfi",
            "shared/src/main/assets/offline-mfi",
        )
    }
}
