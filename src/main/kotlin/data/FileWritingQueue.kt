package data


import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.DEFAULT_PROF_DIR
import java.io.File
import kotlin.concurrent.timer

private val logger = KotlinLogging.logger {}
object FileWritingQueue {

    val writeQueue = Channel<() -> Unit>()
    var totalFileWrites = 0

    fun marketDir(file: String): String = "$DEFAULT_PROF_DIR/markets/$file"

    fun createFileWritingQueue() {
        logger.info { "Creating FileWritingQueue" }
        timer("FileWritingQueue", true, 0, 50) {
            runBlocking {
                println("Writing file")
                writeQueue.receive()()
                totalFileWrites++
            }
        }
    }

    suspend inline fun <reified T : Any> enqueue(file: File, data: T) {
        writeQueue.send {
            val contents = Json.encodeToString<T>(data)
            println("Queueing write for ${file.name}: $contents")
            if (file.canWrite())
                file.writeText(contents)
        }
    }
}
