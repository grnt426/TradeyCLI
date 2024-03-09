
import data.DbClient
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import model.ship.toDock
import model.ship.toOrbit
import notification.NotificationManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import screen.TextAnimationContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BaseTest {

    @BeforeAll
    open fun setup() {
        DbClient.createClient("unittests")
        mockkObject(TextAnimationContainer)
        mockkObject(NotificationManager)
        every { NotificationManager.createNotification(any(), any()) } returns Unit
        every { NotificationManager.errorNotification(any(), any()) } returns Unit
        mockkStatic(::toOrbit)
        every { toOrbit(any(), any(), any()) } returns true
        mockkStatic(::toDock)
        every { toDock(any(), any(), any()) } returns true
    }
}