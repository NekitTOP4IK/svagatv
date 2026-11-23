package com.github.andreyasadchy.xtra.ui.chat

import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.andreyasadchy.xtra.databinding.FragmentEmotesBinding
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.RecentEmote
import com.github.andreyasadchy.xtra.ui.view.GridAutofitLayoutManager
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class EmotesFragment : Fragment() {

    @Inject lateinit var favoriteEmotesManager: FavoriteEmotesManager

    private var _binding: FragmentEmotesBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModels<ChatViewModel>(ownerProducer = { requireParentFragment() })
    private var recentEmotes = emptyList<RecentEmote>()
    private var favoriteNames = emptySet<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEmotesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            val position = requireArguments().getInt(KEY_POSITION)
            val adapter = EmotesAdapter(
                fragment = this@EmotesFragment,
                clickListener = { (parentFragment as? ChatFragment)?.appendEmote(it) },
                emoteQuality = requireContext().prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4",
                longClickListener = { emote ->
                    (parentFragment as? ChatFragment)?.onEmoteLongPressed(emote)
                }
            )
            root.itemAnimator = null
            root.adapter = adapter
            val columnWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50f, resources.displayMetrics).toInt()
            root.layoutManager = GridAutofitLayoutManager(requireContext(), columnWidth)

            when (position) {
                TAB_RECENT -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.recentEmotes.collectLatest {
                                recentEmotes = it
                                updateList(position, adapter)
                            }
                        }
                    }
                    observeAllEmoteUpdates(position, adapter)
                }
                TAB_FAVORITES -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            favoriteEmotesManager.favorites.collectLatest { favs ->
                                favoriteNames = favs
                                updateList(position, adapter)
                            }
                        }
                    }
                    observeAllEmoteUpdates(position, adapter)
                }
                TAB_TWITCH -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.userEmotesUpdated.collectLatest {
                                updateList(position, adapter)
                            }
                        }
                    }
                    updateList(position, adapter)
                }
                else -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.thirdPartyEmotesUpdated.collectLatest {
                                updateList(position, adapter)
                            }
                        }
                    }
                    updateList(position, adapter)
                }
            }
        }
    }

    private fun observeAllEmoteUpdates(position: Int, adapter: EmotesAdapter) {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.userEmotesUpdated.collectLatest {
                    updateList(position, adapter)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.thirdPartyEmotesUpdated.collectLatest {
                    updateList(position, adapter)
                }
            }
        }
        updateList(position, adapter)
    }

    private fun collectAllAvailableEmotes(): List<Emote> {
        val personalEmotes = viewModel.userStvEmoteSetId?.let { setId ->
            synchronized(viewModel.personalEmoteSets) {
                viewModel.personalEmoteSets[setId]
            }
        } ?: emptyList()
        val twitch = synchronized(viewModel.userEmotes) { viewModel.userEmotes.toList() }
        val thirdParty = synchronized(viewModel.thirdPartyEmotes) { viewModel.thirdPartyEmotes.toList() }
        return personalEmotes + thirdParty + twitch
    }

    private fun updateList(position: Int, adapter: EmotesAdapter) {
        when (position) {
            TAB_RECENT -> {
                if (recentEmotes.isNotEmpty()) {
                    val all = collectAllAvailableEmotes()
                    val byName = all.associateBy { it.name }
                    adapter.submitList(recentEmotes.mapNotNull { byName[it.name] })
                } else {
                    adapter.submitList(emptyList())
                }
            }
            TAB_FAVORITES -> {
                if (favoriteNames.isNotEmpty()) {
                    val all = collectAllAvailableEmotes()
                    val byName = all.associateBy { it.name }
                    adapter.submitList(favoriteNames.mapNotNull { byName[it] })
                } else {
                    adapter.submitList(emptyList())
                }
            }
            TAB_CHANNEL_STV -> {
                val personal = viewModel.userStvEmoteSetId?.let { setId ->
                    synchronized(viewModel.personalEmoteSets) {
                        viewModel.personalEmoteSets[setId]
                    }
                } ?: emptyList()
                val channelStv = synchronized(viewModel.thirdPartyEmotes) {
                    viewModel.thirdPartyEmotes.filter { it.source == Emote.CHANNEL_STV }
                }
                adapter.submitList((personal + channelStv).distinctBy { it.name })
            }
            TAB_CHANNEL_BTTV_FFZ -> {
                val channelOthers = synchronized(viewModel.thirdPartyEmotes) {
                    viewModel.thirdPartyEmotes.filter {
                        it.source == Emote.CHANNEL_BTTV || it.source == Emote.CHANNEL_FFZ
                    }
                }
                adapter.submitList(channelOthers)
            }
            TAB_TWITCH -> {
                val twitch = synchronized(viewModel.userEmotes) { viewModel.userEmotes.toList() }
                adapter.submitList(twitch)
            }
            TAB_GLOBAL -> {
                val globals = synchronized(viewModel.thirdPartyEmotes) {
                    viewModel.thirdPartyEmotes.filter {
                        it.source == Emote.GLOBAL_STV || it.source == Emote.GLOBAL_BTTV || it.source == Emote.GLOBAL_FFZ
                    }
                }
                adapter.submitList(globals)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (binding.root.layoutManager as? GridAutofitLayoutManager)?.updateWidth()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAB_RECENT = 0
        const val TAB_FAVORITES = 1
        const val TAB_CHANNEL_STV = 2
        const val TAB_CHANNEL_BTTV_FFZ = 3
        const val TAB_TWITCH = 4
        const val TAB_GLOBAL = 5
        const val TAB_COUNT = 6

        private const val KEY_POSITION = "position"

        fun newInstance(position: Int): EmotesFragment {
            return EmotesFragment().apply {
                arguments = Bundle().apply {
                    putInt(KEY_POSITION, position)
                }
            }
        }
    }
}
