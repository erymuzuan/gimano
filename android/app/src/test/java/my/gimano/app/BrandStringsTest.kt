package my.gimano.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The app is Manotu everywhere users can see it; "gimano" survives only in resource names, IDs and file formats. */
class BrandStringsTest {
    private fun values(path: String): Map<String, String> {
        val f = listOf(File(path), File("app/$path")).first { it.exists() }
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(f.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test fun noFormerBrandInVisibleStrings() {
        for (p in listOf("src/main/res/values/strings.xml", "src/main/res/values-ms/strings.xml")) {
            val bad = values(p).filterValues { it.contains("gimano", ignoreCase = true) }
            assertTrue("$p still shows the former brand: $bad", bad.isEmpty())
        }
    }

    @Test fun appIsCalledManotu() {
        assertTrue(values("src/main/res/values/strings.xml")["app_name"] == "Manotu")
        assertTrue(values("src/main/res/values/strings.xml")["restore_from_gimano"] == "Restore from backup")
        assertTrue(values("src/main/res/values-ms/strings.xml")["restore_from_gimano"] == "Pulihkan dari sandaran")
    }
}
