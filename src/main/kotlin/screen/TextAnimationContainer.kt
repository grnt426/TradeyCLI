package screen

import com.varabyte.kotter.foundation.anim.TextAnim
import com.varabyte.kotter.foundation.anim.textAnimOf
import com.varabyte.kotter.runtime.Session
import kotlin.time.Duration.Companion.milliseconds

object TextAnimationContainer {

    val positiveNotificationBlink = TextAnim.Template(listOf(" + ", " - "), 500.milliseconds)
    val newNotificationBlink = TextAnim.Template(listOf(" o ", " - "), 500.milliseconds)
    val errorNotificationBlink = TextAnim.Template(listOf(" ! ", " - "), 250.milliseconds)

    var positiveNotification: TextAnim? = null
    var errorNotification: TextAnim? = null
    var newNotification: TextAnim? = null

    fun Session.createAnimations() {
        positiveNotification = textAnimOf(positiveNotificationBlink)
        errorNotification = textAnimOf(errorNotificationBlink)
        newNotification = textAnimOf(newNotificationBlink)
    }
}
