package data


import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.DEFAULT_PROF_DIR
import java.io.File

object FileWritingQueue {

    val writeQueue = Channel<() -> Unit>()

    fun marketDir(file: String): String = "$DEFAULT_PROF_DIR/markets/$file"

    suspend fun createFileWrite() {
        supervisorScope {
            while (true)
                writeQueue.receive()()
        }
    }

    suspend inline fun <reified T : Any> enqueue(file: File, data: T) {
        writeQueue.send {
            if (file.canWrite())
                file.writeText(Json.encodeToString<T>(data))
        }
    }
}
