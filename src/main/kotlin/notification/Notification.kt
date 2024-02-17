package notification

import com.varabyte.kotter.foundation.anim.TextAnim
import java.awt.Color
import java.time.Instant

class Notification(
    val toast: String,
    val created: Instant,
    val textAnim: TextAnim,
    val animColor: Color,
    val message: String,
    var animDone: Boolean = false,
) {

    fun markRead() {
        textAnim.currFrame = textAnim.lastFrame
        animDone = true
    }
}

