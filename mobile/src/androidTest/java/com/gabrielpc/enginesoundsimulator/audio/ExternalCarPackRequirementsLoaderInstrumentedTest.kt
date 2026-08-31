package com.gabrielpc.enginesoundsimulator.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalCarPackRequirementsLoaderInstrumentedTest {
    @Test
    fun rootOnlyLoaderReadsExactRequirementsWithoutOpeningRuntimeJson() {
        val requirements = ExternalCarPackRequirementsLoader.parse(StringReader(rootCatalog()))

        assertEquals(
            setOf(EngineAudioPackRequirement("family-pack", 7, "a".repeat(64))),
            requirements,
        )
    }

    @Test
    fun inlineRuntimeBodyIsRejectedByTheRootOnlyContract() {
        val invalid = rootCatalog().replace(
            "\"runtimeAssetName\":\"families/family.json\"",
            "\"runtimeIndex\":{}",
        )

        assertThrows(IllegalArgumentException::class.java) {
            ExternalCarPackRequirementsLoader.parse(StringReader(invalid))
        }
    }

    private fun rootCatalog(): String = """
        {
          "schema":"byd-car-atlas-catalog-v2",
          "catalogVersion":2,
          "cars":[{}],
          "families":[{
            "id":"family",
            "assetDirectory":"family",
            "packRequirement":{
              "packId":"family-pack",
              "packVersion":7,
              "manifestSha256":"${"a".repeat(64)}"
            },
            "runtimeAssetName":"families/family.json",
            "runtimeBytes":123,
            "runtimeSha256":"${"b".repeat(64)}",
            "eagerCapabilities":{
              "perspectives":["cabin","exterior"],
              "effectControls":{
                "cabin":{"hasTurboEvent":false,"runtimeTriggers":[]},
                "exterior":{"hasTurboEvent":false,"runtimeTriggers":[]}
              }
            }
          }]
        }
    """.trimIndent()
}
