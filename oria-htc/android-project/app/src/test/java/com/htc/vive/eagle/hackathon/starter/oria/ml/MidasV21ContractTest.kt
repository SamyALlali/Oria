package com.htc.vive.eagle.hackathon.starter.oria.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class MidasV21ContractTest {
    @Test fun normalizedCameraCoordinatesMapDirectlyToDepthRaster() {
        val landscape = MidasV21Transform(1920, 1080)
        val portrait = MidasV21Transform(1080, 1920)
        for (value in listOf(0f, .1f, .39f, .5f, .61f, .9f, 1f)) {
            assertEquals(value * 256f, landscape.outputX(value), .0001f)
            assertEquals(value * 256f, landscape.outputY(value), .0001f)
            assertEquals(landscape.outputX(value), portrait.outputX(value), .0001f)
            assertEquals(landscape.outputY(value), portrait.outputY(value), .0001f)
        }
    }

    @Test fun bundledModelHasPinnedOfficialReleaseFingerprint() {
        val asset = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "app/src/main/assets/${MidasV21DepthEstimator.MODEL_ASSET}") }
            .firstOrNull { it.isFile } ?: error("Cannot find bundled MiDaS model")
        assertEquals(66_764_249L, asset.length())
        val digest = MessageDigest.getInstance("SHA-256")
        asset.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        assertEquals("2d8c6cb8f415229daf1eb041024208e2608c9f98e17c81cc7c6ecb449c56fd58",
            digest.digest().joinToString("") { "%02x".format(it) })
        assertTrue(File(asset.parentFile, "LICENSE_MIDAS.txt").isFile)
    }
}
