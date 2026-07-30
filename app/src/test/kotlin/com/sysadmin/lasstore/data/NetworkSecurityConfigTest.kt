package com.sysadmin.lasstore.data

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSecurityConfigTest {
    @Test
    fun systemTrustRemainsEnabledWithoutStaticPinsOrCleartext() {
        val config = locateConfig()
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(config)

        val baseConfig = document.getElementsByTagName("base-config").item(0)
        assertEquals("false", baseConfig.attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)

        val certificates = document.getElementsByTagName("certificates")
        assertTrue(certificates.length > 0)
        assertEquals("system", certificates.item(0).attributes.getNamedItem("src").nodeValue)
        assertFalse(document.getElementsByTagName("pin-set").length > 0)
        assertFalse(document.getElementsByTagName("pin").length > 0)
    }

    private fun locateConfig(): File {
        val workingDirectory = File(
            System.getProperty("user.dir") ?: error("user.dir is unavailable")
        )
        return sequenceOf(
            File(workingDirectory, "app/src/main/res/xml/network_security_config.xml"),
            File(workingDirectory, "src/main/res/xml/network_security_config.xml"),
        ).firstOrNull(File::isFile)
            ?: error("network_security_config.xml not found from $workingDirectory")
    }
}
