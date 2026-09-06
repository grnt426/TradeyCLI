import app.App
import cli.LineMode
import data.ensureRuntimeDirectories
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

/**
 * No arguments, or `bridge` followed by its options: the console ([bridge.Bridge]). Anything else:
 * line mode, one command and out ([LineMode]).
 */
fun main(args: Array<String>) {
    ensureRuntimeDirectories()
    val code = try {
        when {
            args.isEmpty() -> {
                logger.info { "TradeyCLI starting" }
                bridge.Bridge.run(emptyList())
            }
            args.first() == "bridge" -> bridge.Bridge.run(args.drop(1))
            else -> LineMode().run(args.toList())
        }
    } finally {
        App.shutdown()
    }
    exitProcess(code)
}
