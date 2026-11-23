package com.github.andreyasadchy.xtra.ui.chat

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.svagaplus.SvagaBadgeCard
import com.github.andreyasadchy.xtra.svagaplus.SvagaRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SvagaBadgeCardDialog : BottomSheetDialogFragment() {

    @Inject
    lateinit var svagaRepository: SvagaRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val pad = dp(20f)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(18f), pad, dp(22f))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val args = requireArguments()
        val page = args.getString(ARG_PAGE).orEmpty()
        val fallbackTitle = args.getString(ARG_TITLE).orEmpty()
        val fallbackUrl = args.getString(ARG_IMAGE_URL).orEmpty()
        val fallbackRarity = args.getString(ARG_RARITY)

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconBox = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56f), dp(56f)).apply {
                marginEnd = dp(14f)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8f).toFloat()
                setColor(Color.parseColor("#272735"))
                setStroke(dp(1f), Color.parseColor("#38384A"))
            }
            val iconPad = dp(8f)
            setPadding(iconPad, iconPad, iconPad, iconPad)
        }

        val titlesCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameView = TextView(ctx).apply {
            text = fallbackTitle.ifBlank { "Бейдж Свага+" }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val rarityBadgeView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4f) }
            isVisible = false
        }

        titlesCol.addView(nameView)
        titlesCol.addView(rarityBadgeView)
        headerRow.addView(iconBox)
        headerRow.addView(titlesCol)
        root.addView(headerRow)

        val progress = ProgressBar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28f), dp(28f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16f)
            }
            isVisible = page.isNotBlank()
        }
        root.addView(progress)

        val descriptionView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14f) }
            isVisible = false
        }
        root.addView(descriptionView)

        val howToGetBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6f).toFloat()
                setColor(Color.parseColor("#1A5266EB"))
                setStroke(dp(1f), Color.parseColor("#405266EB"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12f) }
            isVisible = false
        }
        val howLabel = TextView(ctx).apply {
            text = getString(R.string.svaga_badge_how_to_get)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8C9EFF"))
        }
        val howText = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2f) }
        }
        howToGetBox.addView(howLabel)
        howToGetBox.addView(howText)
        root.addView(howToGetBox)

        val footerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16f) }
            isVisible = false
        }
        val ownersView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.78f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val openPageBtn = MaterialButton(ctx).apply {
            text = getString(R.string.svaga_badge_open_page)
            isAllCaps = false
            cornerRadius = dp(20f)
        }
        footerRow.addView(ownersView)
        footerRow.addView(openPageBtn)
        root.addView(footerRow)

        if (fallbackUrl.isNotBlank()) {
            loadBadgeDrawable(iconBox, fallbackUrl)
        }
        if (!fallbackRarity.isNullOrBlank()) {
            bindRarityTag(rarityBadgeView, fallbackRarity, null)
        }

        if (page.isNotBlank()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val card = svagaRepository.getBadgeCard(page)
                progress.isVisible = false
                if (card != null) {
                    applyCardData(card, iconBox, nameView, rarityBadgeView, descriptionView, howToGetBox, howText, footerRow, ownersView, openPageBtn)
                }
            }
        }

        return root
    }

    override fun onStart() {
        super.onStart()
        (view?.parent as? View)?.let { parent ->
            val behavior = BottomSheetBehavior.from(parent)
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun applyCardData(
        card: SvagaBadgeCard,
        iconBox: ImageView,
        nameView: TextView,
        rarityBadgeView: TextView,
        descriptionView: TextView,
        howToGetBox: LinearLayout,
        howText: TextView,
        footerRow: LinearLayout,
        ownersView: TextView,
        openPageBtn: MaterialButton,
    ) {
        nameView.text = card.name
        loadBadgeDrawable(iconBox, card.imageUrl)
        bindRarityTag(rarityBadgeView, card.rarity, card.kindLabelRu())

        if (!card.description.isNullOrBlank()) {
            descriptionView.text = card.description
            descriptionView.isVisible = true
        }
        if (!card.howToGet.isNullOrBlank()) {
            howText.text = card.howToGet
            howToGetBox.isVisible = true
        }
        ownersView.text = card.ownersLabelRu()
        openPageBtn.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, card.pageUrl.toUri()))
            } catch (_: Exception) {
            }
        }
        footerRow.isVisible = true
    }

    private fun bindRarityTag(view: TextView, rarity: String?, fallbackKind: String?) {
        val (label, bgHex, fgHex) = when (rarity) {
            "common" -> Triple("Обычный", "#2B2D38", "#D0D2DC")
            "rare" -> Triple("Редкий", "#1A365D", "#63B3ED")
            "epic" -> Triple("Эпический", "#3B1F5E", "#D6BCFA")
            "mythic" -> Triple("Мифический", "#591C2F", "#FBB6CE")
            "service" -> Triple("Служебный", "#1F473E", "#81E6D9")
            else -> Triple(fallbackKind ?: "Свага+", "#272735", "#C3C3CC")
        }
        view.text = label
        view.setTextColor(Color.parseColor(fgHex))
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12f).toFloat()
            setColor(Color.parseColor(bgHex))
        }
        view.isVisible = true
    }

    private fun loadBadgeDrawable(targetView: ImageView, url: String) {
        val ctx = context ?: return
        val animate = ctx.prefs().getBoolean(C.ANIMATED_EMOTES, true)
        ctx.imageLoader.enqueue(
            ImageRequest.Builder(ctx)
                .data(url)
                .target(onSuccess = { image ->
                    val drawable = image.asDrawable(resources)
                    if (drawable is Animatable && animate) {
                        drawable.start()
                    }
                    targetView.setImageDrawable(drawable)
                })
                .build()
        )
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    companion object {
        const val TAG = "SvagaBadgeCardDialog"
        private const val ARG_PAGE = "arg_page"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_IMAGE_URL = "arg_image_url"
        private const val ARG_RARITY = "arg_rarity"

        fun newInstance(page: String?, title: String?, imageUrl: String?, rarity: String? = null): SvagaBadgeCardDialog {
            return SvagaBadgeCardDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_PAGE, page)
                    putString(ARG_TITLE, title)
                    putString(ARG_IMAGE_URL, imageUrl)
                    putString(ARG_RARITY, rarity)
                }
            }
        }
    }
}
