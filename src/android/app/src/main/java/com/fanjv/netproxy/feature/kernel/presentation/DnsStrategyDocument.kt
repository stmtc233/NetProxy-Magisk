package com.fanjv.netproxy.feature.kernel.presentation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** 只修改三种解析策略，其余 DNS、路由规则和自定义字段原样保留。 */
internal data class DnsStrategyDocument(
    val root: JsonObject,
    val remote: String,
    val direct: String,
    val node: String,
) {
    fun encode(): String {
        require(listOf(remote, direct, node).all { it in strategies })
        val dns = root["dns"]?.jsonObject ?: error("配置中缺少 dns 分区")
        val rules = dns["rules"] as? JsonArray ?: error("配置中缺少 DNS 规则")
        val updatedRules = rules.map { element ->
            val rule = element as? JsonObject ?: return@map element
            if (rule.stringValue("action") != "route") return@map element
            val strategy = when (rule.stringValue("server")) {
                "dns-proxy" -> remote
                "dns-direct" -> direct
                else -> return@map element
            }
            JsonObject(rule.toMutableMap().apply {
                if (strategy.isEmpty()) remove("strategy")
                else put("strategy", JsonPrimitive(strategy))
            })
        }
        val route = root["route"]?.jsonObject ?: error("配置中缺少 route 分区")
        val original = route["default_domain_resolver"]
        val resolver: MutableMap<String, JsonElement> = when (original) {
            is JsonObject -> original.toMutableMap()
            is JsonPrimitive -> mutableMapOf<String, JsonElement>("server" to original)
            else -> mutableMapOf("server" to JsonPrimitive("dns-direct"))
        }
        if (node.isEmpty()) resolver.remove("strategy")
        else resolver["strategy"] = JsonPrimitive(node)
        val updatedRoute = route.toMutableMap()
        if (node.isNotEmpty() || original is JsonObject) {
            updatedRoute["default_domain_resolver"] = JsonObject(resolver)
        }
        return prettyJson.encodeToString(
            JsonElement.serializer(),
            JsonObject(root.toMutableMap().apply {
                put("dns", JsonObject(dns.toMutableMap().apply { put("rules", JsonArray(updatedRules)) }))
                put("route", JsonObject(updatedRoute))
            })
        ) + "\n"
    }

    companion object {
        val strategies = listOf("", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")
        private val parser = Json { ignoreUnknownKeys = false }
        private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

        fun parse(content: String): DnsStrategyDocument {
            val root = parser.parseToJsonElement(content).jsonObject
            val dns = root["dns"]?.jsonObject ?: error("配置中缺少 dns 分区")
            val rules = dns["rules"] as? JsonArray ?: error("配置中缺少 DNS 规则")
            fun ruleStrategy(server: String): String = rules.asSequence()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it.stringValue("action") == "route" && it.stringValue("server") == server }
                ?.stringValue("strategy").orEmpty()
            require(rules.any {
                val rule = it as? JsonObject
                rule?.stringValue("action") == "route" && rule.stringValue("server") == "dns-proxy"
            })
            require(rules.any {
                val rule = it as? JsonObject
                rule?.stringValue("action") == "route" && rule.stringValue("server") == "dns-direct"
            })
            val route = root["route"]?.jsonObject ?: error("配置中缺少 route 分区")
            val node = (route["default_domain_resolver"] as? JsonObject)?.stringValue("strategy").orEmpty()
            return DnsStrategyDocument(root, ruleStrategy("dns-proxy"), ruleStrategy("dns-direct"), node)
        }
    }
}

private fun JsonObject.stringValue(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
