package data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecretsTest {

    private val dir = Files.createTempDirectory("tradey-secrets").toFile()

    @Test
    fun `missing file reads as null`() {
        assertNull(readSecret(File(dir, "nope.secret").path))
    }

    @Test
    fun `blank file reads as null`() {
        val file = File(dir, "blank.secret").apply { writeText("   \r\n") }
        assertNull(readSecret(file.path))
    }

    @Test
    fun `token is trimmed of surrounding whitespace`() {
        val file = File(dir, "token.secret").apply { writeText("  abc.def.ghi\r\n") }
        assertEquals("abc.def.ghi", readSecret(file.path))
    }
}
