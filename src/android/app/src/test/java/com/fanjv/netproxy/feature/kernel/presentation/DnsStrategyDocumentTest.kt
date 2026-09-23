package com.fanjv.netproxy.feature.kernel.presentation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class DnsStrategyDocumentTest {
    private val source = """{
        "custom": {"keep": true},
        "dns": {"strategy": "prefer_ipv4", "servers": [{"tag":"dns-proxy"}], "rules": [
            {"action":"route", "server":"dns-proxy", "rule_set":"foreign"},
            {"action":"route", "server":"dns-direct", "strategy":"ipv6_only"},
            {"action":"route", "server":"dns-proxy", "strategy":"prefer_ipv6"},
            {"action":"reject", "server":"dns-direct"}
        ]},
        "route": {"default_domain_resolver":"dns-direct", "rules":[{"action":"route"}]}
    }""".trimIndent()

    @Test fun changesOnlyThreeStrategiesAndPreservesOtherFields() {
        val document = DnsStrategyDocument.parse(source)
        assertEquals("", document.remote)
        assertEquals("ipv6_only", document.direct)
        assertEquals("", document.node)
        val root = Json.parseToJsonElement(
            document.copy(remote = "ipv4_only", direct = "prefer_ipv6", node = "prefer_ipv4").encode()
        ).jsonObject
        val rules = root.getValue("dns").jsonObject.getValue("rules").jsonArray
        assertEquals("ipv4_only", rules[0].jsonObject.getValue("strategy").jsonPrimitive.content)
        assertEquals("prefer_ipv6", rules[1].jsonObject.getValue("strategy").jsonPrimitive.content)
        assertEquals("ipv4_only", rules[2].jsonObject.getValue("strategy").jsonPrimitive.content)
        assertEquals("reject", rules[3].jsonObject.getValue("action").jsonPrimitive.content)
        assertEquals("foreign", rules[0].jsonObject.getValue("rule_set").jsonPrimitive.content)
        assertEquals("prefer_ipv4", root.getValue("dns").jsonObject.getValue("strategy").jsonPrimitive.content)
        assertEquals("prefer_ipv4", root.getValue("route").jsonObject
            .getValue("default_domain_resolver").jsonObject.getValue("strategy").jsonPrimitive.content)
        assertEquals("true", root.getValue("custom").jsonObject.getValue("keep").jsonPrimitive.content)
    }

    @Test fun resetLeavesStringResolverAndRemovesRuleOverrides() {
        val document = DnsStrategyDocument.parse(source)
        val root = Json.parseToJsonElement(document.copy(direct = "").encode()).jsonObject
        assertEquals("dns-direct", root.getValue("route").jsonObject
            .getValue("default_domain_resolver").jsonPrimitive.content)
        val direct = root.getValue("dns").jsonObject.getValue("rules").jsonArray[1].jsonObject
        assertEquals(false, direct.containsKey("strategy"))
    }
}
