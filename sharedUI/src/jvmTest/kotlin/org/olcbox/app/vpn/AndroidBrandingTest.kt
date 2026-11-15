package org.olcbox.app.vpn

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidBrandingTest {
    @Test
    fun androidForkHasItsOwnNameAndPackage() {
        val build = File("../androidApp/build.gradle.kts").readText()
        val manifest = File("../androidApp/src/main/AndroidManifest.xml").readText()
        val strings = File("../androidApp/src/main/res/values/strings.xml").readText()

        assertTrue("applicationId = \"ru.hukm.ilyukhaline\"" in build)
        assertTrue("android:label=\"ИлюхаLine\"" in manifest)
        assertTrue("<string name=\"qs_tile_label\">ИлюхаLine</string>" in strings)
    }
}
