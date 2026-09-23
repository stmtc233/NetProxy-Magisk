package com.fanjv.netproxy.feature.kernel.presentation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalRulesDocumentTest {
    @Test
    fun preservesUnknownFieldsAndEditsCommonMatchers() {
        val source = """
            {
              "version": 3,
              "custom_root": true,
              "rules": [
                {
                  "domain": "example.com",
                  "port": 443,
                  "network": ["tcp"],
                  "custom_rule": "keep"
                }
              ]
            }
        """.trimIndent()
        val document = LocalRulesDocument.parse(source)
        val rule = document.rules.single()

        assertEquals("example.com", rule.domain)
        assertEquals("443", rule.port)
        assertNull(document.validate())

        val encoded = Json.parseToJsonElement(
            document.copy(
                rules = listOf(rule.copy(domainSuffix = "example.org\nexample.net"))
            ).encode()
        ).jsonObject
        assertTrue(encoded["custom_root"]!!.jsonPrimitive.content.toBoolean())
        val encodedRule = encoded["rules"]!!.jsonArray.single().jsonObject
        assertEquals("keep", encodedRule["custom_rule"]!!.jsonPrimitive.content)
        assertEquals("example.com", encodedRule["domain"]!!.jsonPrimitive.content)
        assertEquals(443, encodedRule["port"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, encodedRule["domain_suffix"]!!.jsonArray.size)
    }

    @Test
    fun validatesEmptyRulesAndPorts() {
        val empty = LocalRulesDocument.parse("""{"version":1,"rules":[{}]}""")
        assertTrue(empty.validate()!!.contains("至少"))

        val invalidPort = empty.copy(
            rules = listOf(LocalRuleDraft.empty().copy(port = "0\n65536"))
        )
        assertTrue(invalidPort.validate()!!.contains("端口"))

        val invalidRange = empty.copy(
            rules = listOf(LocalRuleDraft.empty().copy(portRange = "2000:1000"))
        )
        assertTrue(invalidRange.validate()!!.contains("范围"))
    }

    @Test
    fun currentRepositoryRulesRoundTrip() {
        val directory = sequenceOf(
            File("../../module/config/singbox/rules/local"),
            File("../module/config/singbox/rules/local"),
            File("src/module/config/singbox/rules/local"),
        ).first(File::isDirectory)

        LocalRuleKind.entries.forEach { kind ->
            val original = Json.parseToJsonElement(
                File(directory, kind.documentId.substringAfterLast('/')).readText()
            )
            val encoded = Json.parseToJsonElement(
                LocalRulesDocument.parse(original.toString()).encode()
            )
            assertEquals(original, encoded)
        }
    }
}
