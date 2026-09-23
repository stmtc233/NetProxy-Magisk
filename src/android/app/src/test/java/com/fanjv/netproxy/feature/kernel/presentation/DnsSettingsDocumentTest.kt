package com.fanjv.netproxy.feature.kernel.presentation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DnsSettingsDocumentTest {
    @Test
    fun parsesCurrentDefaultAndPreservesUnknownFields() {
        val document = DnsSettingsDocument.parse(DEFAULT_DNS)

        assertEquals("dns-proxy", document.finalServer)
        assertEquals("prefer_ipv4", document.remoteStrategy)
        assertEquals("prefer_ipv4", document.directStrategy)
        assertEquals("", document.nodeStrategy)
        assertTrue(document.optimistic)
        assertEquals(listOf("dns-proxy", "cloudflare", "hosts"), document.serverTags)
        assertEquals("cloudflare\nhosts", document.servers.first().groupServers)

        val encoded = Json.parseToJsonElement(
            document.copy(remoteStrategy = "prefer_ipv6", reverseMapping = true).encode()
        ).jsonObject
        val dns = encoded["dns"]!!.jsonObject
        assertEquals("keep", dns["custom"]!!.jsonPrimitive.content)
        assertEquals("prefer_ipv6", dns["strategy"]!!.jsonPrimitive.content)
        assertTrue(dns["reverse_mapping"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "keep-server",
            dns["servers"]!!.jsonArray[1].jsonObject["custom"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun editsIndependentRemoteDirectAndNodeStrategies() {
        val document = DnsSettingsDocument.parse(
            DEFAULT_DNS,
            """
                {"route":{"default_domain_resolver":"dns-direct","rules":[]}}
            """.trimIndent(),
        ).copy(
            remoteStrategy = "prefer_ipv6",
            directStrategy = "ipv4_only",
            nodeStrategy = "ipv6_only",
        )

        val dns = Json.parseToJsonElement(document.encode()).jsonObject["dns"]!!.jsonObject
        assertEquals(
            "prefer_ipv6",
            dns["rules"]!!.jsonArray[0].jsonObject["strategy"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "ipv4_only",
            dns["rules"]!!.jsonArray[1].jsonObject["strategy"]!!.jsonPrimitive.content,
        )
        val route = Json.parseToJsonElement(document.encodeRoute()!!).jsonObject["route"]!!.jsonObject
        assertEquals("dns-direct", route["default_domain_resolver"]!!.jsonObject["server"]!!.jsonPrimitive.content)
        assertEquals("ipv6_only", route["default_domain_resolver"]!!.jsonObject["strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun combinedEncodingPreservesOtherTopLevelFields() {
        val fullConfig = """
            {
              "dns": ${Json.parseToJsonElement(DEFAULT_DNS).jsonObject["dns"]},
              "route": {
                "default_domain_resolver": "dns-direct",
                "rules": [{"outbound":"Proxy"}]
              },
              "log": {"level":"info"}
            }
        """.trimIndent()
        val document = DnsSettingsDocument.parse(fullConfig, fullConfig).copy(
            remoteStrategy = "prefer_ipv4",
            directStrategy = "ipv4_only",
            nodeStrategy = "prefer_ipv6",
        )

        val encoded = Json.parseToJsonElement(document.encodeCombined()).jsonObject
        assertEquals("info", encoded["log"]!!.jsonObject["level"]!!.jsonPrimitive.content)
        assertEquals(1, encoded["route"]!!.jsonObject["rules"]!!.jsonArray.size)
        assertEquals(
            "prefer_ipv6",
            encoded["route"]!!.jsonObject["default_domain_resolver"]!!
                .jsonObject["strategy"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun editsGroupRemoteServerAndHosts() {
        var document = DnsSettingsDocument.parse(DEFAULT_DNS)
        document = document.copy(
            servers = document.servers.mapIndexed { index, server ->
                when (index) {
                    0 -> server.copy(groupServers = "cloudflare")
                    1 -> server.copy(server = "dns.google", path = "/resolve")
                    else -> server.copy(predefinedHosts = "dns.google 8.8.8.8 8.8.4.4")
                }
            }
        )

        assertNull(document.validate())
        val servers = Json.parseToJsonElement(document.encode()).jsonObject["dns"]!!
            .jsonObject["servers"]!!.jsonArray
        assertEquals(1, servers[0].jsonObject["servers"]!!.jsonArray.size)
        assertEquals("dns.google", servers[1].jsonObject["server"]!!.jsonPrimitive.content)
        assertEquals("/resolve", servers[1].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(
            2,
            servers[2].jsonObject["predefined"]!!.jsonObject["dns.google"]!!.jsonArray.size,
        )
    }

    @Test
    fun rejectsDuplicateAndMissingGroupTags() {
        val document = DnsSettingsDocument.parse(DEFAULT_DNS)
        assertTrue(
            document.copy(servers = document.servers + document.servers.first()).validate()!!
                .contains("重复")
        )
        assertTrue(
            document.copy(
                servers = document.servers.mapIndexed { index, server ->
                    if (index == 0) server.copy(groupServers = "missing") else server
                }
            ).validate()!!.contains("不存在")
        )
    }

    @Test
    fun currentRepositoryDnsConfigurationRoundTrips() {
        val configFile = sequenceOf(
            File("../../module/config/singbox/config.json"),
            File("../module/config/singbox/config.json"),
            File("src/module/config/singbox/config.json"),
        ).first(File::isFile)
        val original = Json.parseToJsonElement(configFile.readText()).jsonObject["dns"]!!.jsonObject
        val document = DnsSettingsDocument.parse(
            JsonObject(mapOf("dns" to original)).toString()
        )

        assertNull(document.validate())
        val encoded = Json.parseToJsonElement(document.encode()).jsonObject["dns"]!!.jsonObject
        assertEquals(original, encoded)
    }

    private companion object {
        val DEFAULT_DNS = """
            {
              "dns": {
                "servers": [
                  { "tag": "dns-proxy", "type": "group", "servers": ["cloudflare", "hosts"] },
                  {
                    "tag": "cloudflare",
                    "type": "https",
                    "server": "cloudflare-dns.com",
                    "path": "/dns-query",
                    "domain_resolver": "hosts",
                    "detour": "Proxy",
                    "custom": "keep-server"
                  },
                  {
                    "tag": "hosts",
                    "type": "hosts",
                    "predefined": { "cloudflare-dns.com": ["1.1.1.1", "1.0.0.1"] }
                  }
                ],
                "rules": [
                  { "action": "route", "server": "dns-proxy" },
                  { "action": "route", "server": "dns-direct" }
                ],
                "final": "dns-proxy",
                "strategy": "prefer_ipv4",
                "optimistic": true,
                "custom": "keep"
              }
            }
        """.trimIndent()
    }
}
