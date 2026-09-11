package com.fanjv.netproxy.feature.kernel.presentation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal enum class LocalRuleKind(val documentId: String) {
    Proxy("singbox/rules/local/proxy.json"),
    Direct("singbox/rules/local/direct.json"),
    Block("singbox/rules/local/block.json");

    companion object {
        fun fromRoute(value: String): LocalRuleKind = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true) ||
                it.documentId.substringAfterLast('/').substringBeforeLast('.') == value
        } ?: Proxy
    }
}

internal data class LocalRuleDraft(
    val raw: JsonObject,
    val domain: String,
    val domainSuffix: String,
    val domainKeyword: String,
    val domainRegex: String,
    val ipCidr: String,
    val port: String,
    val portRange: String,
    val network: String,
    val protocol: String,
) {
    val conditionCount: Int
        get() = fields.sumOf { field -> field.value.lineValues().size }

    fun encode(): JsonObject {
        val values = raw.toMutableMap()
        fields.forEach { field ->
            updateList(values, field.key, field.value, raw.stringList(field.key))
        }
        return JsonObject(values)
    }

    fun validate(): String? {
        port.lineValues().forEach { value ->
            if (value.toIntOrNull()?.takeIf { it in 1..65535 } == null) {
                return "端口必须在 1 到 65535 之间：$value"
            }
        }
        portRange.lineValues().forEach { value ->
            val limits = value.split(':')
            if (limits.size != 2 || limits.any {
                    it.toIntOrNull()?.takeIf { port -> port in 1..65535 } == null
                } || limits[0].toInt() > limits[1].toInt()
            ) {
                return "端口范围格式无效：$value"
            }
        }
        return null
    }

    private val fields: List<RuleField>
        get() = listOf(
            RuleField("domain", domain),
            RuleField("domain_suffix", domainSuffix),
            RuleField("domain_keyword", domainKeyword),
            RuleField("domain_regex", domainRegex),
            RuleField("ip_cidr", ipCidr),
            RuleField("port", port),
            RuleField("port_range", portRange),
            RuleField("network", network),
            RuleField("protocol", protocol),
        )

    companion object {
        fun parse(raw: JsonObject) = LocalRuleDraft(
            raw = raw,
            domain = raw.textList("domain"),
            domainSuffix = raw.textList("domain_suffix"),
            domainKeyword = raw.textList("domain_keyword"),
            domainRegex = raw.textList("domain_regex"),
            ipCidr = raw.textList("ip_cidr"),
            port = raw.textList("port"),
            portRange = raw.textList("port_range"),
            network = raw.textList("network"),
            protocol = raw.textList("protocol"),
        )

        fun empty() = parse(JsonObject(emptyMap()))
    }
}

internal data class LocalRulesDocument(
    val root: JsonObject,
    val rules: List<LocalRuleDraft>,
) {
    fun encode(): String {
        val values = root.toMutableMap()
        values["rules"] = JsonArray(rules.map(LocalRuleDraft::encode))
        return prettyJson.encodeToString(JsonElement.serializer(), JsonObject(values)) + "\n"
    }

    fun validate(): String? {
        rules.forEachIndexed { index, rule ->
            rule.validate()?.let { return "规则 ${index + 1}：$it" }
            if (rule.conditionCount == 0 && rule.raw.keys.none { it !in supportedFields }) {
                return "规则 ${index + 1} 至少需要一个匹配条件"
            }
        }
        return null
    }

    companion object {
        private val parser = Json { ignoreUnknownKeys = false }
        private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }
        private val supportedFields = setOf(
            "domain", "domain_suffix", "domain_keyword", "domain_regex", "ip_cidr",
            "port", "port_range", "network", "protocol",
        )

        fun parse(content: String): LocalRulesDocument {
            val root = parser.parseToJsonElement(content).jsonObject
            val rules = (root["rules"] as? JsonArray).orEmpty().map { rule ->
                LocalRuleDraft.parse(rule.jsonObject)
            }
            return LocalRulesDocument(root = root, rules = rules)
        }
    }
}

private data class RuleField(val key: String, val value: String)

private fun JsonObject.textList(key: String): String = stringList(key)?.joinToString("\n").orEmpty()

private fun JsonObject.stringList(key: String): List<String>? = when (val value = get(key)) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> listOfNotNull(value.contentOrNull)
    else -> null
}

private fun String.lineValues(): List<String> = lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .toList()

private fun updateList(
    values: MutableMap<String, JsonElement>,
    key: String,
    value: String,
    original: List<String>?,
) {
    val updated = value.lineValues()
    if (updated == original.orEmpty()) return
    if (updated.isEmpty()) values.remove(key) else {
        values[key] = JsonArray(updated.map { item ->
            item.toIntOrNull()?.let(::JsonPrimitive).takeIf { key == "port" } ?: JsonPrimitive(item)
        })
    }
}
