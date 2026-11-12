package io.github.witzig0hast.cmfexport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Renders the start screen as PNG files under build/screenshots (light + dark) for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp-xxhdpi")
class ScreenshotTest {
    private fun shoot(name: String) {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val now = Instant.now()
        Settings(ctx).apply {
            serverUrl = "http://192.168.1.10:8080"; token = "x"; autoSync = true; intervalMin = 15
            lastSync = iso(now.minus(Duration.ofMinutes(6)))!!
            gadgetbridgeUri = "content://x"
            dashboard = Dashboard(
                SleepNight(now.minus(Duration.ofHours(9)), now.minus(Duration.ofMinutes(95)), 82.0, 251.0, 96.0, 23.0, "com.nothing.cmf.watch"),
                8432, LocalDate.now(zone).toString(), 67, now.minus(Duration.ofMinutes(4)), 76, now.minus(Duration.ofMinutes(6)),
            )
        }
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = act.findViewById<View>(R.id.root)
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val out = File(System.getProperty("user.dir"), "build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun light() = shoot("main_light")
    @Config(qualifiers = "+night") @Test fun dark() = shoot("main_dark")
}
