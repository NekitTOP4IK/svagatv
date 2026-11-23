package com.github.andreyasadchy.xtra.svagaplus

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SvagaAliasManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: SvagaPlusApi,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aliasesMap = ConcurrentHashMap<String, String>()
    private val _aliases = MutableStateFlow<Map<String, String>>(emptyMap())
    val aliases: StateFlow<Map<String, String>> = _aliases.asStateFlow()

    init {
        loadFromPrefs()
    }

    fun isEnabled(): Boolean =
        context.prefs().getBoolean(PREF_SVAGA_ALIASES_ENABLED, true)

    fun getAlias(login: String?): String? {
        if (login.isNullOrBlank() || !isEnabled()) return null
        return aliasesMap[login.trim().lowercase()]
    }

    fun getRawAlias(login: String?): String? {
        if (login.isNullOrBlank()) return null
        return aliasesMap[login.trim().lowercase()]
    }

    fun getAllAliasesSnapshot(): Map<String, String> = HashMap(aliasesMap)

    fun setAlias(login: String, alias: String) {
        val normLogin = login.trim().lowercase()
        val normAlias = alias.trim().take(MAX_ALIAS_LENGTH)
        if (!LOGIN_REGEX.matches(normLogin)) return

        if (normAlias.isEmpty()) {
            removeAlias(normLogin)
            return
        }

        aliasesMap[normLogin] = normAlias
        persistAndPublish()

        val token = context.tokenPrefs().getString("svaga_viewer_token", null)
        if (!token.isNullOrBlank()) {
            scope.launch {
                api.upsertServerAlias(normLogin, normAlias, token)
            }
        }
    }

    fun removeAlias(login: String) {
        val normLogin = login.trim().lowercase()
        val removed = aliasesMap.remove(normLogin) ?: return
        persistAndPublish()

        val token = context.tokenPrefs().getString("svaga_viewer_token", null)
        if (!token.isNullOrBlank()) {
            scope.launch {
                if (!api.deleteServerAlias(removed, token)) {
                    api.deleteServerAlias(normLogin, token)
                }
            }
        }
    }

    suspend fun syncWithServer(token: String) {
        val serverMap = api.fetchServerAliases(token) ?: return
        val localOnly = HashMap<String, String>()
        for ((login, alias) in aliasesMap) {
            if (!serverMap.containsKey(login)) {
                localOnly[login] = alias
            }
        }
        aliasesMap.putAll(serverMap)
        persistAndPublish()

        for ((login, alias) in localOnly) {
            api.upsertServerAlias(login, alias, token)
        }
    }

    fun exportAliasesJson(): String {
        val sorted = aliasesMap.entries.sortedBy { it.key }
        return buildJsonObject {
            for ((k, v) in sorted) {
                put(k, JsonPrimitive(v))
            }
        }.toString()
    }

    fun importAliasesJson(rawJson: String): Int {
        val obj = try {
            json.parseToJsonElement(rawJson) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return 0

        var imported = 0
        for ((k, v) in obj) {
            if (imported >= MAX_IMPORT_COUNT) break
            val normLogin = k.trim().lowercase()
            val normAlias = v.jsonPrimitive.contentOrNull?.trim()?.take(MAX_ALIAS_LENGTH) ?: continue
            if (LOGIN_REGEX.matches(normLogin) && normAlias.isNotEmpty()) {
                aliasesMap[normLogin] = normAlias
                imported++
            }
        }
        if (imported > 0) {
            persistAndPublish()
            val token = context.tokenPrefs().getString("svaga_viewer_token", null)
            if (!token.isNullOrBlank()) {
                scope.launch { syncWithServer(token) }
            }
        }
        return imported
    }

    private fun loadFromPrefs() {
        val raw = context.prefs().getString(PREF_SVAGA_ALIASES_JSON, null) ?: return
        try {
            val obj = json.parseToJsonElement(raw) as? JsonObject ?: return
            for ((k, v) in obj) {
                val login = k.trim().lowercase()
                val alias = v.jsonPrimitive.contentOrNull?.trim() ?: continue
                if (LOGIN_REGEX.matches(login) && alias.isNotEmpty()) {
                    aliasesMap[login] = alias
                }
            }
            _aliases.value = HashMap(aliasesMap)
        } catch (_: Exception) {
        }
    }

    private fun persistAndPublish() {
        val jsonString = exportAliasesJson()
        context.prefs().edit {
            putString(PREF_SVAGA_ALIASES_JSON, jsonString)
        }
        _aliases.value = HashMap(aliasesMap)
    }

    companion object {
        const val PREF_SVAGA_ALIASES_ENABLED = "svaga_aliases_enabled"
        private const val PREF_SVAGA_ALIASES_JSON = "svaga_aliases_json"
        private const val MAX_ALIAS_LENGTH = 64
        private const val MAX_IMPORT_COUNT = 1000
        private val LOGIN_REGEX = Regex("""^[a-z0-9_]{3,25}$""")
    }
}
