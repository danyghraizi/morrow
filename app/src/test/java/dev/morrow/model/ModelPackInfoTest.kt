package dev.morrow.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The bundled file is fetched by Gradle from the values in gradle.properties and
 * verified at runtime against ModelPack.Info. If the two drift, the app refuses
 * to start, so pin them together here.
 */
class ModelPackInfoTest {

    private val props: Map<String, String> by lazy {
        val file = File("../gradle.properties")
        check(file.exists()) { "gradle.properties not found from ${File(".").absolutePath}" }
        file.readLines()
            .filter { it.contains('=') && !it.trimStart().startsWith("#") }
            .associate { line ->
                val (key, value) = line.split('=', limit = 2)
                key.trim() to value.trim()
            }
    }

    @Test
    fun `asset name matches the gradle model file`() {
        assertEquals(props.getValue("morrow.modelFile"), ModelPack.Info.ASSET_NAME)
    }

    @Test
    fun `size and checksum match the fetched artifact`() {
        assertEquals(props.getValue("morrow.modelBytes"), ModelPack.Info.BYTES.toString())
        assertEquals(props.getValue("morrow.modelSha"), ModelPack.Info.SHA256)
    }

    @Test
    fun `download url points at the same file name`() {
        assertEquals(true, props.getValue("morrow.modelUrl").endsWith("/" + ModelPack.Info.ASSET_NAME))
    }
}
