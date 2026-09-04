package startup

import kotlinx.coroutines.runBlocking
import model.exceptions.BootFailure
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The boot pre-checks must fail before any network call, with a message that says which file is
 * missing and what to do about it.
 */
class BootManagerTest {

    private val missingSecret = File(Files.createTempDirectory("tradey-boot").toFile(), "missing.secret").path

    @Test
    fun `start without an agent token names the file and suggests NEW`() {
        val failure = assertFailsWith<BootFailure> {
            runBlocking { BootManager.normalStart(agentSymbol = "NOBODY", agentTokenPath = missingSecret) }
        }
        val message = failure.message ?: ""
        assertTrue(message.contains(missingSecret), message)
        assertTrue(message.contains("NEW"), message)
    }

    @Test
    fun `new without an account token names the file and the account portal`() {
        val failure = assertFailsWith<BootFailure> {
            runBlocking { BootManager.bootstrapNew(accountTokenPath = missingSecret) }
        }
        val message = failure.message ?: ""
        assertTrue(message.contains(missingSecret), message)
        assertTrue(message.contains("my.spacetraders.io"), message)
    }
}
