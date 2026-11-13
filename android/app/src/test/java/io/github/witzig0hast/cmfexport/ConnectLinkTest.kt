package io.github.witzig0hast.cmfexport

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectLinkTest {
    @Test
    fun websiteLinkConfiguresTheServer() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val link = Uri.parse("cmfexport://connect?server=https%3A%2F%2Fcmf.example.de&token=cmf-abcdefghjkmn")
        Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_VIEW, link).setClass(ctx, MainActivity::class.java)).setup()
        val s = Settings(ctx)
        assertEquals("https://cmf.example.de", s.serverUrl)
        assertEquals("cmf-abcdefghjkmn", s.token)
        assertTrue(s.autoSync)
    }
}
