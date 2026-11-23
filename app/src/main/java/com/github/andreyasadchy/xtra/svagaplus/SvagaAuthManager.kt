package com.github.andreyasadchy.xtra.svagaplus

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.util.tokenPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SvagaAuthManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: SvagaPlusApi,
    private val aliasManager: SvagaAliasManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _account = MutableStateFlow<SvagaViewerAccount?>(loadStoredAccount())
    val account: StateFlow<SvagaViewerAccount?> = _account.asStateFlow()

    val currentToken: String?
        get() = _account.value?.token?.takeIf { it.isNotBlank() }

    init {
        if (currentToken != null) {
            scope.launch {
                refreshAccount()
            }
        }
    }

    fun getViewerConnectUrl(): String = "${api.getBackendUrl()}/viewer-connect"

    fun getRedirectUri(): String = "${api.getBackendUrl()}/viewer-connect"

    suspend fun completeOAuthCode(code: String, redirectUri: String = getRedirectUri()): Result<SvagaViewerAccount> {
        val token = api.linkViewerTwitch(code, redirectUri)
            ?: return Result.failure(IllegalStateException("Не удалось обменять код авторизации Свага+"))
        return saveAndHydrateToken(token)
    }

    suspend fun saveAndHydrateToken(rawToken: String): Result<SvagaViewerAccount> {
        val cleanToken = rawToken.trim().removePrefix("Bearer ").trim()
        if (cleanToken.isEmpty()) {
            return Result.failure(IllegalArgumentException("Пустой токен"))
        }
        val (me, status) = api.getViewerMe(cleanToken)
        if (me == null) {
            if (status == 401) clearAccount()
            return Result.failure(IllegalStateException("Токен отклонён сервером Свага+ ($status)"))
        }
        persistAccount(me)
        _account.value = me
        scope.launch {
            aliasManager.syncWithServer(cleanToken)
        }
        return Result.success(me)
    }

    suspend fun refreshAccount(): SvagaViewerAccount? {
        val token = currentToken ?: return null
        val (me, status) = api.getViewerMe(token)
        if (me != null) {
            persistAccount(me)
            _account.value = me
            aliasManager.syncWithServer(token)
            return me
        }
        if (status == 401) {
            clearAccount()
        }
        return _account.value
    }

    fun logout() {
        clearAccount()
    }

    private fun loadStoredAccount(): SvagaViewerAccount? {
        val prefs = context.tokenPrefs()
        val token = prefs.getString(KEY_SVAGA_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val login = prefs.getString(KEY_SVAGA_LOGIN, null).orEmpty()
        return SvagaViewerAccount(
            token = token,
            twitchLogin = login,
            avatarUrl = prefs.getString(KEY_SVAGA_AVATAR, null),
            telegramLinked = prefs.getBoolean(KEY_SVAGA_TG_LINKED, false),
            colorBanned = prefs.getBoolean(KEY_SVAGA_COLOR_BANNED, false),
            colorBanReason = prefs.getString(KEY_SVAGA_COLOR_BAN_REASON, null),
            lastCheckedAt = prefs.getLong(KEY_SVAGA_CHECKED_AT, 0L),
        )
    }

    private fun persistAccount(account: SvagaViewerAccount) {
        context.tokenPrefs().edit {
            putString(KEY_SVAGA_TOKEN, account.token)
            putString(KEY_SVAGA_LOGIN, account.twitchLogin)
            putString(KEY_SVAGA_AVATAR, account.avatarUrl)
            putBoolean(KEY_SVAGA_TG_LINKED, account.telegramLinked)
            putBoolean(KEY_SVAGA_COLOR_BANNED, account.colorBanned)
            putString(KEY_SVAGA_COLOR_BAN_REASON, account.colorBanReason)
            putLong(KEY_SVAGA_CHECKED_AT, account.lastCheckedAt)
        }
    }

    private fun clearAccount() {
        context.tokenPrefs().edit {
            remove(KEY_SVAGA_TOKEN)
            remove(KEY_SVAGA_LOGIN)
            remove(KEY_SVAGA_AVATAR)
            remove(KEY_SVAGA_TG_LINKED)
            remove(KEY_SVAGA_COLOR_BANNED)
            remove(KEY_SVAGA_COLOR_BAN_REASON)
            remove(KEY_SVAGA_CHECKED_AT)
        }
        _account.value = null
    }

    companion object {
        private const val KEY_SVAGA_TOKEN = "svaga_viewer_token"
        private const val KEY_SVAGA_LOGIN = "svaga_viewer_login"
        private const val KEY_SVAGA_AVATAR = "svaga_viewer_avatar"
        private const val KEY_SVAGA_TG_LINKED = "svaga_viewer_tg_linked"
        private const val KEY_SVAGA_COLOR_BANNED = "svaga_viewer_color_banned"
        private const val KEY_SVAGA_COLOR_BAN_REASON = "svaga_viewer_color_ban_reason"
        private const val KEY_SVAGA_CHECKED_AT = "svaga_viewer_checked_at"
    }
}
