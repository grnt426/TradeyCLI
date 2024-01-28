import com.varabyte.kotter.foundation.anim.TextAnim
import java.time.LocalDateTime

class Notification(
    val toast: String,
    val created: LocalDateTime,
    val textAnim: TextAnim,
    val message: String,
    var animDone: Boolean = false
) {

    fun markRead() {
        textAnim.currFrame = textAnim.lastFrame
        animDone = true
    }
}