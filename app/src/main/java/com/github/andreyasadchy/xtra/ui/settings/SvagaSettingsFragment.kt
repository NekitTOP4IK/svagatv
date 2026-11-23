package com.github.andreyasadchy.xtra.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.svagaplus.SvagaAliasManager
import com.github.andreyasadchy.xtra.svagaplus.SvagaAuthDialog
import com.github.andreyasadchy.xtra.svagaplus.SvagaAuthManager
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SvagaSettingsFragment : MaterialPreferenceFragment() {

    @Inject
    lateinit var authManager: SvagaAuthManager

    @Inject
    lateinit var aliasManager: SvagaAliasManager

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.svaga_preferences, rootKey)

        findPreference<Preference>("svaga_account_status")?.setOnPreferenceClickListener {
            if (childFragmentManager.findFragmentByTag(SvagaAuthDialog.TAG) == null) {
                SvagaAuthDialog().show(childFragmentManager, SvagaAuthDialog.TAG)
            }
            true
        }

        findPreference<Preference>("svaga_paste_token")?.setOnPreferenceClickListener {
            showPasteTokenDialog()
            true
        }

        findPreference<Preference>("svaga_account_logout")?.setOnPreferenceClickListener {
            authManager.logout()
            Toast.makeText(requireContext(), "Свага+: выполнен выход", Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<Preference>("svaga_manage_aliases")?.setOnPreferenceClickListener {
            showManageAliasesDialog()
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            listView.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                authManager.account.collectLatest { account ->
                    val statusPref = findPreference<Preference>("svaga_account_status")
                    val logoutPref = findPreference<Preference>("svaga_account_logout")
                    if (account != null && account.twitchLogin.isNotBlank()) {
                        val tgLabel = getString(
                            if (account.telegramLinked) R.string.svaga_tg_linked_yes else R.string.svaga_tg_linked_no
                        )
                        statusPref?.title = "@${account.twitchLogin}"
                        statusPref?.summary = getString(R.string.svaga_account_connected_summary, account.twitchLogin, tgLabel)
                        logoutPref?.isVisible = true
                    } else {
                        statusPref?.title = getString(R.string.svaga_account_connect)
                        statusPref?.summary = getString(R.string.svaga_account_disconnected_summary)
                        logoutPref?.isVisible = false
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                aliasManager.aliases.collectLatest { map ->
                    findPreference<Preference>("svaga_manage_aliases")?.summary =
                        getString(R.string.svaga_manage_aliases_summary, map.size)
                }
            }
        }
    }

    private fun showPasteTokenDialog() {
        val ctx = requireContext()
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, resources.displayMetrics).toInt()
        val input = EditText(ctx).apply {
            hint = "JWT viewer_token…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val box = LinearLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        ctx.getAlertDialogBuilder()
            .setTitle(R.string.svaga_paste_token_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = input.text?.toString().orEmpty()
                if (raw.isNotBlank()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val res = authManager.saveAndHydrateToken(raw)
                        res.onSuccess { acc ->
                            Toast.makeText(ctx, "Подключён @${acc.twitchLogin}", Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            Toast.makeText(ctx, err.message ?: "Ошибка токена", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showManageAliasesDialog() {
        val ctx = requireContext()
        val entries = aliasManager.getAllAliasesSnapshot().entries.sortedBy { it.key }
        val items = entries.map { "${it.value} (@${it.key})" }.toTypedArray()

        val builder = ctx.getAlertDialogBuilder()
            .setTitle(R.string.svaga_manage_aliases_title)

        if (items.isNotEmpty()) {
            builder.setItems(items) { _, which ->
                val selected = entries.getOrNull(which) ?: return@setItems
                aliasManager.removeAlias(selected.key)
                Toast.makeText(ctx, "Удалён алиас @${selected.key}", Toast.LENGTH_SHORT).show()
            }
        } else {
            builder.setMessage("Список алиасов пока пуст. Нажмите на сообщение зрителя в чате, чтобы задать ему кастомный ник.")
        }

        builder
            .setPositiveButton("Экспорт JSON") { _, _ ->
                val clipboard = getSystemService(ctx, ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("svaga_aliases", aliasManager.exportAliasesJson()))
                Toast.makeText(ctx, "Алиасы скопированы в буфер обмена", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Импорт из буфера") { _, _ ->
                val clipboard = getSystemService(ctx, ClipboardManager::class.java)
                val raw = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                val count = aliasManager.importAliasesJson(raw)
                Toast.makeText(ctx, "Импортировано алиасов: $count", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }
}
