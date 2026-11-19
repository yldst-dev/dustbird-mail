package app.dustbird.mail.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAccessTest {
    @Test
    fun theSystemQuestionIsUsedWhileItCanStillAppearAndSettingsOtherwise() {
        assertTrue(NotificationAccess.canAsk(granted = false, askedBefore = false, rationale = false))
        assertTrue(NotificationAccess.canAsk(granted = false, askedBefore = true, rationale = true))
        assertFalse(NotificationAccess.canAsk(granted = false, askedBefore = true, rationale = false))
        assertFalse(NotificationAccess.canAsk(granted = true, askedBefore = false, rationale = false))
    }
}
