package com.example.activeperception.acquire

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevents a build that advertises the eight-method plan but omits its Neural-AE model. */
class BundledNaeAssetTest {
    @Test fun bundledWeightsAreTheValidatedExperimentModel() {
        val file = File("src/main/assets/nae_hist_scalar_3x3.bin")
        assertTrue("missing bundled Neural-AE weights: ${file.absolutePath}", file.isFile)
        val bytes = file.readBytes()
        assertEquals(374_570, bytes.size)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "db6090752eb50612e59a1405888df2f9530bf9e66c82dbd1bf4dbdef9b99992b",
            sha
        )
        NeuralAeNet(bytes) // validates header, layer order and every tensor length
    }
}
