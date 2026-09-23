package com.fanjv.netproxy.feature.kernel.presentation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class DnsServerDraft(
    val raw: JsonObject,
    val tag: String,
    val type: String,
    val server: String,
    val serverPort: String,
    val path: String,
    val detour: String,
    val domainResolver: String,
    val groupServers: String,
    val predefinedHosts: String,
) {
    fun encode(): JsonObject {
        val values = raw.toMutableMap()
        val originalType = raw.stringValue("type").orEmpty()
        if (type != originalType) {
            listOf("server", "server_port", "path", "servers", "predefined").forEach(values::remove)
        }
        updateString(values, "tag", tag, raw.stringValue("tag"))
        updateString(values, "type", type, raw.stringValue("type"))
        if (type in remoteTypes) {
            updateString(values, "server", server, raw.stringValue("server"))
            updateInteger(values, "server_port", serverPort, raw.integerText("server_port"))
            if (type == "https") updateString(values, "path", path, raw.stringValue("path"))
        }
        if (type !in setOf("group", "hosts")) {
            updateString(values, "detour", detour, raw.stringValue("detour"))
            updateString(values, "domain_resolver", domainResolver, raw.stringValue("domain_resolver"))
        } else {
            values.remove("detour")
            values.remove("domain_resolver")
        }

        val originalMembers = raw.stringList("servers")?.joinToString("\n")
        if (type == "group" && groupServers != originalMembers.orEmpty()) {
            val members = groupServers.lines().map(String::trim).filter(String::isNotEmpty)
            if (members.isEmpty()) values.remove("servers") else {
                values["servers"] = JsonArray(members.map(::JsonPrimitive))
            }
        }

        val originalHosts = raw["predefined"]?.let(::formatHosts).orEmpty()
        if (type == "hosts" && predefinedHosts != originalHosts) {
            val hosts = parseHosts(predefinedHosts)
            if (hosts.isEmpty()) values.remove("predefined") else values["predefined"] = JsonObject(hosts)
        }
        return JsonObject(values)
    }

    fun validate(): String? {
        if (tag.isBlank()) return "DNS 服务器标签不能为空"
        if (type.isBlank()) return "DNS 服务器类型不能为空"
        if (type == "group" && groupServers.lineValues().isEmpty()) return "DNS 分组至少需要一个成员"
        if (type in remoteTypes && server.isBlank()) return "远程 DNS 服务器地址不能为空"
        if (serverPort.isNotBlank() && serverPort.toIntOrNull()?.takeIf { it in 1..65535 } == null) {
            return "DNS 服务器端口必须在 1 到 65535 之间"
        }
        return null
    }

    companion object {
        val supportedTypes = listOf("https", "tls", "quic", "udp", "tcp", "local", "hosts", "group")
        val remoteTypes = setOf("https", "tls", "quic", "udp", "tcp")

        fun parse(raw: JsonObject) = DnsServerDraft(
            raw = raw,
            tag = raw.stringValue("tag").orEmpty(),
            type = raw.stringValue("type").orEmpty(),
            server = raw.stringValue("server").orEmpty(),
            serverPort = raw.integerText("server_port").orEmpty(),
            path = raw.stringValue("path").orEmpty(),
            detour = raw.stringValue("detour").orEmpty(),
            domainResolver = raw.stringValue("domain_resolver").orEmpty(),
            groupServers = raw.stringList("servers")?.joinToString("\n").orEmpty(),
            predefinedHosts = raw["predefined"]?.let(::formatHosts).orEmpty(),
        )

        fun empty() = parse(
            JsonObject(
                mapOf(
                    "tag" to JsonPrimitive("dns-new"),
                    "type" to JsonPrimitive("https"),
                    "server" to JsonPrimitive("dns.example.com"),
                    "path" to JsonPrimitive("/dns-query"),
                )
            )
        )
    }
}

internal data class DnsSettingsDocument(
    val root: JsonObject,
    val dnsRaw: JsonObject,
    val routeRoot: JsonObject? = null,
    val servers: List<DnsServerDraft>,
    val finalServer: String,
    val remoteStrategy: String,
    val directStrategy: String,
    val nodeStrategy: String,
    val optimistic: Boolean,
    val reverseMapping: Boolean,
    val disableCache: Boolean,
    val ruleCount: Int,
) {
    val serverTags: List<String>
        get() = servers.map { it.tag }.filter(String::isNotBlank)

    fun encode(): String {
        val dns = dnsRaw.toMutableMap()
        dns["servers"] = JsonArray(servers.map(DnsServerDraft::encode))
        updateString(dns, "final", finalServer, dnsRaw.stringValue("final"))
        updateString(dns, "strategy", remoteStrategy, dnsRaw.stringValue("strategy"))
        updateBoolean(dns, "reverse_mapping", reverseMapping, dnsRaw.booleanValue("reverse_mapping"))
        updateBoolean(dns, "disable_cache", disableCache, dnsRaw.booleanValue("disable_cache"))
        updateOptimistic(dns, optimistic, dnsRaw["optimistic"])
        updateRouteStrategies(
            dns = dns,
            remoteStrategy = remoteStrategy,
            directStrategy = directStrategy,
            originalRemoteStrategy = dnsRaw.routeStrategy("dns-proxy"),
            originalDirectStrategy = dnsRaw.routeStrategy("dns-direct"),
        )

        val document = root.toMutableMap()
        document["dns"] = JsonObject(dns)
        return prettyJson.encodeToString(JsonElement.serializer(), JsonObject(document)) + "\n"
    }

    fun encodeRoute(): String? {
        val routeDocument = routeRoot ?: return null
        val route = routeDocument["route"]?.jsonObject ?: return null
        val original = route["default_domain_resolver"]
        val resolver = when {
            nodeStrategy.isBlank() && original is JsonPrimitive -> original
            nodeStrategy.isBlank() && original == null -> null
            else -> {
                val server = when (original) {
                    is JsonPrimitive -> original.contentOrNull.orEmpty()
                    is JsonObject -> original.stringValue("server").orEmpty()
                    else -> ""
                }.ifBlank { "dns-direct" }
                JsonObject(buildMap {
                    put("server", JsonPrimitive(server))
                    if (nodeStrategy.isNotBlank()) put("strategy", JsonPrimitive(nodeStrategy))
                    if (original is JsonObject) {
                        original.forEach { (key, value) ->
                            if (key != "server" && key != "strategy") put(key, value)
                        }
                    }
                })
            }
        }
        val updatedRoute = route.toMutableMap()
        if (resolver == null) updatedRoute.remove("default_domain_resolver")
        else updatedRoute["default_domain_resolver"] = resolver
        val document = routeDocument.toMutableMap()
        document["route"] = JsonObject(updatedRoute)
        return prettyJson.encodeToString(JsonElement.serializer(), JsonObject(document)) + "\n"
    }

    fun encodeCombined(): String {
        val dnsDocument = parser.parseToJsonElement(encode()).jsonObject
        val routeDocument = encodeRoute()?.let { parser.parseToJsonElement(it).jsonObject }
        if (routeDocument == null || "route" !in routeDocument) return encode()
        val document = dnsDocument.toMutableMap()
        document["route"] = routeDocument.getValue("route")
        return prettyJson.encodeToString(JsonElement.serializer(), JsonObject(document)) + "\n"
    }

    fun validate(): String? {
        val tags = servers.map { it.tag.trim() }
        if (tags.any(String::isBlank)) return "DNS 服务器标签不能为空"
        if (tags.size != tags.distinct().size) return "DNS 服务器标签不能重复"
        servers.forEach { server -> server.validate()?.let { return it } }
        listOf(remoteStrategy, directStrategy, nodeStrategy).forEach { value ->
            if (value !in strategies) return "DNS 域名策略无效"
        }
        if (finalServer.isNotBlank() && finalServer !in tags) return "默认 DNS 服务器不存在"
        servers.filter { it.type == "group" }.forEach { group ->
            val missing = group.groupServers.lineValues().firstOrNull { it !in tags }
            if (missing != null) return "DNS 分组 ${group.tag} 引用了不存在的服务器 $missing"
        }
        return null
    }

    companion object {
        val strategies = listOf("", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")
        private val parser = Json { ignoreUnknownKeys = false }
        private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

        fun parse(content: String, routeContent: String? = null): DnsSettingsDocument {
            val root = parser.parseToJsonElement(content).jsonObject
            val dns = root["dns"]?.jsonObject ?: error("配置中缺少 dns 分区")
            val routeRoot = routeContent?.let { parser.parseToJsonElement(it).jsonObject }
            val route = routeRoot?.get("route")?.jsonObject
            val servers = (dns["servers"] as? JsonArray).orEmpty().map { server ->
                DnsServerDraft.parse(server.jsonObject)
            }
            val optimisticElement = dns["optimistic"]
            val optimistic = when (optimisticElement) {
                is JsonPrimitive -> optimisticElement.booleanOrNull ?: false
                is JsonObject -> optimisticElement["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                else -> false
            }
            return DnsSettingsDocument(
                root = root,
                dnsRaw = dns,
                routeRoot = routeRoot,
                servers = servers,
                finalServer = dns.stringValue("final").orEmpty(),
                remoteStrategy = dns.routeStrategy("dns-proxy"),
                directStrategy = dns.routeStrategy("dns-direct"),
                nodeStrategy = (route?.get("default_domain_resolver") as? JsonObject)
                    ?.stringValue("strategy").orEmpty(),
                optimistic = optimistic,
                reverseMapping = dns.booleanValue("reverse_mapping") ?: false,
                disableCache = dns.booleanValue("disable_cache") ?: false,
                ruleCount = (dns["rules"] as? JsonArray)?.size ?: 0,
            )
        }
    }
}

private fun updateRouteStrategies(
    dns: MutableMap<String, JsonElement>,
    remoteStrategy: String,
    directStrategy: String,
    originalRemoteStrategy: String,
    originalDirectStrategy: String,
) {
    val rules = dns["rules"] as? JsonArray ?: return
    val updated = rules.map { element ->
        val rule = element as? JsonObject ?: return@map element
        if (rule.stringValue("action") != "route") return@map element
        val server = rule.stringValue("server")
        val strategy = when (server) {
            "dns-proxy" -> remoteStrategy.takeIf { it != originalRemoteStrategy }
            "dns-direct" -> directStrategy.takeIf { it != originalDirectStrategy }
            else -> return@map element
        } ?: return@map element
        val values = rule.toMutableMap()
        if (strategy.isBlank()) values.remove("strategy")
        else values["strategy"] = JsonPrimitive(strategy)
        JsonObject(values)
    }
    dns["rules"] = JsonArray(updated)
}

private fun JsonObject.routeStrategy(server: String): String {
    val rules = get("rules") as? JsonArray ?: return stringValue("strategy").orEmpty()
    return rules.asSequence()
        .mapNotNull { it as? JsonObject }
        .firstOrNull {
            it.stringValue("action") == "route" && it.stringValue("server") == server
        }
        ?.stringValue("strategy")
        ?: stringValue("strategy").orEmpty()
}

private fun JsonObject.stringValue(key: String): String? {
    val value = get(key) as? JsonPrimitive ?: return null
    return value.contentOrNull.takeIf { value.isString }
}

private fun JsonObject.integerText(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.toIntOrNull() != null }

private fun JsonObject.booleanValue(key: String): Boolean? =
    (get(key) as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.stringList(key: String): List<String>? =
    (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun String.lineValues(): List<String> = lines().map(String::trim).filter(String::isNotEmpty)

private fun updateString(
    values: MutableMap<String, JsonElement>,
    key: String,
    value: String,
    original: String?,
) {
    if (value == original.orEmpty()) return
    if (value.isBlank()) values.remove(key) else values[key] = JsonPrimitive(value.trim())
}

private fun updateInteger(
    values: MutableMap<String, JsonElement>,
    key: String,
    value: String,
    original: String?,
) {
    if (value == original.orEmpty()) return
    if (value.isBlank()) values.remove(key) else values[key] = JsonPrimitive(value.toInt())
}

private fun updateBoolean(
    values: MutableMap<String, JsonElement>,
    key: String,
    value: Boolean,
    original: Boolean?,
) {
    if (value == (original ?: false)) return
    if (!value && original == null) values.remove(key) else values[key] = JsonPrimitive(value)
}

private fun updateOptimistic(
    values: MutableMap<String, JsonElement>,
    enabled: Boolean,
    original: JsonElement?,
) {
    when (original) {
        is JsonObject -> {
            val current = original["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
            if (current != enabled) {
                values["optimistic"] = JsonObject(original.toMutableMap().apply {
                    put("enabled", JsonPrimitive(enabled))
                })
            }
        }
        is JsonPrimitive -> if (original.booleanOrNull != enabled) {
            values["optimistic"] = JsonPrimitive(enabled)
        }
        else -> if (enabled) values["optimistic"] = JsonPrimitive(true)
    }
}

private fun formatHosts(element: JsonElement): String {
    val hosts = element as? JsonObject ?: return ""
    return hosts.entries.joinToString("\n") { (domain, value) ->
        val addresses = when (value) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            JsonNull -> emptyList()
            else -> emptyList()
        }
        (listOf(domain) + addresses).joinToString(" ")
    }
}

private fun parseHosts(content: String): Map<String, JsonElement> = buildMap {
    content.lineSequence().forEach { line ->
        val values = line.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (values.size < 2 || values.first().startsWith("#")) return@forEach
        put(
            values.first(),
            if (values.size == 2) JsonPrimitive(values[1]) else buildJsonArray {
                values.drop(1).forEach { add(JsonPrimitive(it)) }
            },
        )
    }
}
