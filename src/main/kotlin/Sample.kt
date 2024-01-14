import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotterx.decorations.bordered

class Sample {

    fun main() {
        session{
            section {
                bordered {
                    textLine("Hello World from the Left!")
                    textLine("row 2!")
                }
            }
        }
    }
}