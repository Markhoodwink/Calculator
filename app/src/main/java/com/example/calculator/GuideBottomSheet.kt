package com.example.calculator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView

class GuideBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_guide, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnClose = view.findViewById<TextView>(R.id.btnCloseGuide)
        btnClose?.setOnClickListener {
            dismiss()
        }

        val context = requireContext()
        val isDark = ThemeManager.isDarkTheme(context)

        val root = view.findViewById<View>(R.id.guideRootLayout)
        root?.setBackgroundColor(if (isDark) ThemeManager.COLOR_DARK_SCREEN else ThemeManager.COLOR_LIGHT_SCREEN)

        val dragHandle = view.findViewById<View>(R.id.viewDragHandle)
        dragHandle?.setBackgroundColor(if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)

        val divider = view.findViewById<View>(R.id.viewHeaderDivider)
        divider?.setBackgroundColor(if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)

        val title = view.findViewById<TextView>(R.id.tvGuideTitle)
        title?.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)

        btnClose?.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY)

        val cat1Title = view.findViewById<TextView>(R.id.tvCategory1Title)
        cat1Title?.setTextColor(if (isDark) android.graphics.Color.parseColor("#38BDF8") else android.graphics.Color.parseColor("#0284C7"))

        val cat2Title = view.findViewById<TextView>(R.id.tvCategory2Title)
        cat2Title?.setTextColor(if (isDark) android.graphics.Color.parseColor("#38BDF8") else android.graphics.Color.parseColor("#0284C7"))

        val cardIds = listOf(
            R.id.cardLifehack1, R.id.cardLifehack2, R.id.cardLifehack3, R.id.cardLifehack4, R.id.cardLifehack5,
            R.id.cardMode1, R.id.cardMode2, R.id.cardMode3, R.id.cardMode4, R.id.cardMode5, R.id.cardMode6, R.id.cardMode7
        )

        cardIds.forEach { cardId ->
            view.findViewById<MaterialCardView>(cardId)?.let { card ->
                card.setCardBackgroundColor(if (isDark) ThemeManager.COLOR_DARK_PANEL else ThemeManager.COLOR_LIGHT_PANEL)
                card.strokeColor = if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER
            }
        }

        val itemTitleIds = listOf(
            R.id.tvLifehack1Title, R.id.tvLifehack2Title, R.id.tvLifehack3Title, R.id.tvLifehack4Title, R.id.tvLifehack5Title,
            R.id.tvMode1Title, R.id.tvMode2Title, R.id.tvMode3Title, R.id.tvMode4Title, R.id.tvMode5Title, R.id.tvMode6Title, R.id.tvMode7Title
        )

        itemTitleIds.forEach { id ->
            view.findViewById<TextView>(id)?.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)
        }

        val itemDescIds = listOf(
            R.id.tvLifehack1Desc, R.id.tvLifehack2Desc, R.id.tvLifehack3Desc, R.id.tvLifehack4Desc, R.id.tvLifehack5Desc,
            R.id.tvMode1Desc, R.id.tvMode2Desc, R.id.tvMode3Desc, R.id.tvMode4Desc, R.id.tvMode5Desc, R.id.tvMode6Desc, R.id.tvMode7Desc
        )

        itemDescIds.forEach { id ->
            view.findViewById<TextView>(id)?.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY)
        }
    }
}
