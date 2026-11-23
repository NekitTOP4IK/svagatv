package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteEmotesManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val _favorites = MutableStateFlow(loadFromPrefs())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    fun isFavorite(emoteName: String?): Boolean {
        if (emoteName.isNullOrBlank()) return false
        return _favorites.value.contains(emoteName)
    }

    /** Returns `true` if added to favorites, `false` if removed. */
    fun toggleFavorite(emoteName: String): Boolean {
        val trimmed = emoteName.trim()
        if (trimmed.isEmpty()) return false
        val next = LinkedHashSet(_favorites.value)
        val added = if (next.contains(trimmed)) {
            next.remove(trimmed)
            false
        } else {
            next.add(trimmed)
            true
        }
        context.prefs().edit {
            putStringSet(C.FAVORITE_EMOTES_SET, HashSet(next))
        }
        _favorites.value = next
        return added
    }

    private fun loadFromPrefs(): Set<String> {
        return context.prefs().getStringSet(C.FAVORITE_EMOTES_SET, emptySet())?.toSet().orEmpty()
    }
}
