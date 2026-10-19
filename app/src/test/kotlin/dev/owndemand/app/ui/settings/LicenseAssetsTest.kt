package dev.owndemand.app.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The licence texts shown in Settings ship as assets; they must stay
 * byte-identical to the repository's LICENSE and NOTICE (Apache 2.0 §4
 * requires both to accompany the APK).
 */
class LicenseAssetsTest {
    // Unit tests run with the :app module directory as the working dir.
    private fun repoFile(name: String) = File("..", name)
    private fun asset(name: String) = File("src/main/assets/licenses", name)

    @Test
    fun `bundled LICENSE matches the repository LICENSE`() {
        assertEquals(repoFile("LICENSE").readText(), asset("LICENSE.txt").readText())
    }

    @Test
    fun `bundled NOTICE matches the repository NOTICE`() {
        assertEquals(repoFile("NOTICE").readText(), asset("NOTICE.txt").readText())
    }
}
