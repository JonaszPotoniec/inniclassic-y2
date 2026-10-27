package com.themoon.y1.controllers.menu

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.SparseIntArray
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.CircularBatteryView
import com.themoon.y1.views.ClassicMenuStyle
import com.themoon.y1.views.CustomAnalogClockView
import com.themoon.y1.views.IpodClassicLayout
import com.themoon.y1.views.IpodClassicLists
import com.themoon.y1.views.WidgetBatteryBarView
import java.util.Locale
import java.io.File
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap

/**
 * Controller for the iPod dynamic main menu:
 * Builds dynamic theme menu layout (canvas, widgets, scrollable button list),
 * handles action routing, wheel focus navigation and wrapping loops.
 */
class MainMenuController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val clickFeedback: () -> Unit,
    private val onActionTriggered: (action: String) -> Unit,
    private val onButtonFocusChanged: (element: ThemeManager.MenuElement) -> Unit,
    private val startCoverPanAnimation: (iv: ImageView, maxDx: Int, maxDy: Int) -> Unit,
    private val onLongPressScreenOff: View.OnLongClickListener,
    private val localize: (String) -> String,
    private val onDynamicUiBuilt: (() -> Unit)? = null
) : ScreenController {

    private val layoutMainMenu: ViewGroup = rootView.findViewById(R.id.layout_main_menu)
    private val prefs = activity.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

    var lastMainMenuFocusIndex: Int = 0
    private val currentButtons = ArrayList<LinearLayout>()
    private var isUiActive = false

    // Registry of created widget views mapped to their theme definitions
    val widgetViewRegistry = HashMap<View, ThemeManager.MenuElement>()

    // References to dynamic widgets created during buildDynamicMainMenuUI
    var tvWidgetClock: TextView? = null
    var widgetBatteryView: WidgetBatteryBarView? = null
    var layoutWidgetAlbumContainer: LinearLayout? = null
    var ivWidgetAlbum: ImageView? = null
    var tvWidgetAlbumTitle: TextView? = null
    var tvWidgetAlbumArtist: TextView? = null
    var customAnalogClockView: CustomAnalogClockView? = null
    var customCircularBatteryView: CircularBatteryView? = null
    var ivWidgetFocusImage: ImageView? = null
    var ivAnimatedCover: ImageView? = null

    // Exact scroll offset memory for pixel-perfect focus restoration
    val exactOffsetMemory = HashMap<String, Int>()

    // Stable logical keys keep wheel navigation independent of Android resource IDs
    private val dynamicViewIds = SparseIntArray()

    // Resource ids for the rows (see res/values/ids.xml): a generated id makes Android 4.4 log a warning for each key
    private val rowIds: IntArray by lazy {
        val ids = activity.resources.obtainTypedArray(R.array.main_menu_row_ids)
        IntArray(ids.length()) { ids.getResourceId(it, View.NO_ID) }.also { ids.recycle() }
    }

    private fun dynamicViewId(key: Int): Int {
        if (key == View.NO_ID) return View.NO_ID
        var id = dynamicViewIds.get(key, View.NO_ID)
        if (id == View.NO_ID) {
            id = rowIds.getOrElse(dynamicViewIds.size()) { View.generateViewId() }
            dynamicViewIds.put(key, id)
        }
        return id
    }

    private fun dynamicViewKey(view: View): Int {
        val index = dynamicViewIds.indexOfValue(view.id)
        return if (index < 0) -1 else dynamicViewIds.keyAt(index)
    }

    override fun onEnter(state: ScreenState) {
        buildDynamicMainMenuUI()
    }

    override fun onExit() {
        val curFocus = activity.currentFocus
        val idx = if (curFocus != null) currentButtons.indexOf(curFocus) else -1
        if (idx >= 0) {
            lastMainMenuFocusIndex = idx
        }
        isUiActive = false
    }

    override fun onRefreshUI() {
        buildDynamicMainMenuUI()
    }

    override fun onThemeChanged() {
        // onExit saves the focused item only when the user leaves the main menu
        val idx = activity.currentFocus?.let { currentButtons.indexOf(it) } ?: -1
        if (idx >= 0) lastMainMenuFocusIndex = idx
        buildDynamicMainMenuUI()
    }

    fun buildDynamicMainMenuUI() {
        val safeMenuIndex = lastMainMenuFocusIndex
        val mainMenu = layoutMainMenu
        val statusBarHeightDp = ClassicMenuStyle.STATUS_HEIGHT_DP
        val density = activity.resources.displayMetrics.density
        val safeTopPadding = (statusBarHeightDp * density).toInt()

        mainMenu.setPadding(0, 0, 0, 0)

        val statusBarForWidth = activity.findViewById<View>(R.id.layout_status_bar)
        if (statusBarForWidth != null) {
            val slpMenu = statusBarForWidth.layoutParams
            slpMenu.width = (ClassicMenuStyle.PANE_WIDTH_DP * density).toInt()
            statusBarForWidth.layoutParams = slpMenu
            statusBarForWidth.background = ThemeManager.createStatusBarBackground()
        }

        for (i in 0 until mainMenu.childCount) {
            mainMenu.getChildAt(i).visibility = View.GONE
        }

        var oldCanvas = mainMenu.findViewWithTag<View>("dynamic_canvas")
        while (oldCanvas != null) {
            if (oldCanvas is ViewGroup) {
                oldCanvas.removeAllViews()
            }
            mainMenu.removeView(oldCanvas)
            oldCanvas = mainMenu.findViewWithTag("dynamic_canvas")
        }

        val canvas = FrameLayout(activity).apply {
            tag = "dynamic_canvas"
            setBackgroundColor(ThemeManager.getOverlayBackgroundColor())
            clipChildren = false
            clipToPadding = false
        }
        mainMenu.addView(
            canvas,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        tvWidgetClock = null
        widgetBatteryView = null
        layoutWidgetAlbumContainer = null
        ivWidgetAlbum = null
        tvWidgetAlbumTitle = null
        tvWidgetAlbumArtist = null
        customAnalogClockView = null
        customCircularBatteryView = null
        ivWidgetFocusImage = null
        ivAnimatedCover = null

        val elements = IpodClassicLists.menuElements(ThemeManager.getCurrentTheme().menuElements, activity)
        val buttonElements = ArrayList<ThemeManager.MenuElement>()
        val widgetElements = ArrayList<ThemeManager.MenuElement>()

        for (el in elements) {
            if (el.type == "button") buttonElements.add(el)
            else widgetElements.add(el)
        }

        sortMenuElements(buttonElements)

        widgetViewRegistry.clear()
        val listContainers = HashMap<String, LinearLayout>()

        // 1. Build Widgets
        for (el in widgetElements) {
            var widgetBg = createWidgetBackground(el.bgColor, el.radius)
            val p = (el.padding * density).toInt()
            var createdWidgetView: View? = null

            when (el.type) {
                "list_box" -> {
                    val sv = com.themoon.y1.views.EdgeFadeScrollView(activity).apply {
                        layoutParams = createDynamicLayoutParams(el, density, safeTopPadding)
                        isVerticalScrollBarEnabled = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                        if (widgetBg != null) background = widgetBg
                        visibility = View.VISIBLE
                    }

                    val innerLayout = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(p, p, p, p)
                    }
                    sv.addView(
                        innerLayout,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    )

                    canvas.addView(sv)
                    listContainers[el.id] = innerLayout
                    createdWidgetView = sv

                    val listShadow = View(activity).apply {
                        background = GradientDrawable(
                            GradientDrawable.Orientation.LEFT_RIGHT,
                            intArrayOf(0x50000000, 0x00000000)
                        )
                    }
                    val shadowWidth = (10 * density).toInt()
                    val shadowLp = FrameLayout.LayoutParams(shadowWidth, (el.height * density).toInt()).apply {
                        leftMargin = ((el.x + el.width) * density).toInt()
                        topMargin = (el.y * density).toInt() + safeTopPadding
                    }
                    canvas.addView(listShadow, shadowLp)
                }

                "box" -> {
                    val boxView = ImageView(activity).apply {
                        layoutParams = createDynamicLayoutParams(el, density)
                        if (widgetBg == null) widgetBg = createWidgetBackground("#00000000", el.radius)
                        background = widgetBg
                    }

                    val imgName = if (!el.iconNormal.isNullOrEmpty()) el.iconNormal else el.textNormal
                    if (!imgName.isNullOrEmpty() && imgName != "New Item") {
                        val bmp = ThemeManager.getCustomIcon(imgName, activity, 0)
                        if (bmp != null) {
                            val maxTexSize = 2048
                            if (bmp.width > maxTexSize || bmp.height > maxTexSize) {
                                val ratio = Math.min(maxTexSize.toFloat() / bmp.width, maxTexSize.toFloat() / bmp.height)
                                boxView.setImageBitmap(
                                    Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
                                )
                            } else {
                                boxView.setImageBitmap(bmp)
                            }
                            boxView.scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                    }
                    canvas.addView(boxView)
                    createdWidgetView = boxView
                }

                "widget_clock" -> {
                    val tvClock = TextView(activity).apply {
                        gravity = Gravity.CENTER
                        layoutParams = createDynamicLayoutParams(el, density)
                        setTextColor(ThemeManager.getTextColorPrimary())
                        typeface = ThemeManager.getCustomFontBold()
                        val currentClockSize = if (el.textSize > 0) el.textSize.toFloat() else 48f
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, currentClockSize * density)
                        if (widgetBg != null) background = widgetBg
                        setPadding(p, p, p, p)
                    }
                    tvWidgetClock = tvClock
                    canvas.addView(tvClock)
                    createdWidgetView = tvClock
                }

                "widget_battery" -> {
                    val batView = WidgetBatteryBarView(activity).apply {
                        layoutParams = createDynamicLayoutParams(el, density)
                        setPadding(p, p, p, p)
                    }
                    widgetBatteryView = batView
                    canvas.addView(batView)
                    createdWidgetView = batView
                }

                "widget_album" -> {
                    val albumContainer = LinearLayout(activity)
                    layoutWidgetAlbumContainer = albumContainer
                    val isHorizontal = el.textPosition.equals("left", ignoreCase = true) ||
                        el.textPosition.equals("right", ignoreCase = true)
                    albumContainer.orientation = if (isHorizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                    albumContainer.gravity = Gravity.CENTER
                    albumContainer.layoutParams = createDynamicLayoutParams(el, density)
                    if (widgetBg != null) albumContainer.background = widgetBg
                    albumContainer.setPadding(p, p, p, p)

                    val ivAlbum = ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    ivWidgetAlbum = ivAlbum
                    val pSubtract = el.padding * 2
                    var imgSize = if (isHorizontal) ((el.height - pSubtract) * density).toInt()
                    else ((el.height - pSubtract) * 0.65f * density).toInt()
                    if (imgSize <= 0) imgSize = (110 * density).toInt()
                    val imgLp = LinearLayout.LayoutParams(imgSize, imgSize)

                    val textContainer = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    val textGravity = if (el.textAlign.equals("left", ignoreCase = true)) Gravity.LEFT or Gravity.CENTER_VERTICAL
                    else if (el.textAlign.equals("right", ignoreCase = true)) Gravity.RIGHT or Gravity.CENTER_VERTICAL
                    else Gravity.CENTER
                    textContainer.gravity = textGravity
                    val textContainerLp = if (isHorizontal) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                    else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)

                    val safeWidth = if (el.width > 0) (el.width * density).toInt() else (200 * density).toInt()
                    var availableWidth = if (isHorizontal) safeWidth - imgSize - (15 * density).toInt() - (p * 2)
                    else safeWidth - (p * 2)
                    if (availableWidth <= 0) availableWidth = (150 * density).toInt()
                    val textViewLp = LinearLayout.LayoutParams(availableWidth, ViewGroup.LayoutParams.WRAP_CONTENT)

                    val tvTitle = TextView(activity).apply {
                        layoutParams = textViewLp
                        gravity = textGravity
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(ThemeManager.getTextColorPrimary())
                        typeface = ThemeManager.getCustomFontBold()
                        textSize = if (el.textSize > 0) el.textSize.toFloat() else 16f
                    }
                    tvWidgetAlbumTitle = tvTitle
                    textContainer.addView(tvTitle)

                    val tvArtist = TextView(activity).apply {
                        layoutParams = textViewLp
                        gravity = textGravity
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(ThemeManager.getTextColorSecondary())
                        setTypeface(ThemeManager.getCustomFont(), Typeface.NORMAL)
                        textSize = if (el.textSecondarySize > 0) el.textSecondarySize.toFloat() else 12f
                    }
                    tvWidgetAlbumArtist = tvArtist
                    textContainer.addView(tvArtist)

                    if (el.textPosition.equals("left", ignoreCase = true)) {
                        imgLp.leftMargin = (15 * density).toInt()
                        albumContainer.addView(textContainer, textContainerLp)
                        albumContainer.addView(ivAlbum, imgLp)
                    } else if (el.textPosition.equals("right", ignoreCase = true)) {
                        textContainerLp.leftMargin = (15 * density).toInt()
                        albumContainer.addView(ivAlbum, imgLp)
                        albumContainer.addView(textContainer, textContainerLp)
                    } else if (el.textPosition.equals("top", ignoreCase = true)) {
                        textContainerLp.bottomMargin = (5 * density).toInt()
                        albumContainer.addView(textContainer, textContainerLp)
                        albumContainer.addView(ivAlbum, imgLp)
                    } else {
                        textContainerLp.topMargin = (5 * density).toInt()
                        albumContainer.addView(ivAlbum, imgLp)
                        albumContainer.addView(textContainer, textContainerLp)
                    }

                    canvas.addView(albumContainer)
                    createdWidgetView = albumContainer
                }

                "widget_analog_clock" -> {
                    val analogClock = CustomAnalogClockView(activity).apply {
                        layoutParams = createDynamicLayoutParams(el, density)
                        setPadding(p, p, p, p)
                        if (!el.bgColor.isNullOrBlank()) {
                            try {
                                setClockBackgroundColor(android.graphics.Color.parseColor(el.bgColor.trim()))
                            } catch (_: Exception) {}
                        }
                    }
                    customAnalogClockView = analogClock
                    canvas.addView(analogClock)
                    createdWidgetView = analogClock
                }

                "widget_circular_battery" -> {
                    val circularBat = CircularBatteryView(activity).apply {
                        layoutParams = createDynamicLayoutParams(el, density)
                        setPadding(p, p, p, p)
                        if (el.textSize > 0) setCustomTextSize(el.textSize * density)
                    }
                    customCircularBatteryView = circularBat
                    canvas.addView(circularBat)
                    createdWidgetView = circularBat
                }

                "widget_focus_image" -> {
                    val focusIv = ImageView(activity).apply {
                        layoutParams = createDynamicLayoutParams(el, density)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        if (widgetBg != null) background = widgetBg
                        setPadding(p, p, p, p)
                    }
                    ivWidgetFocusImage = focusIv
                    canvas.addView(focusIv)
                    createdWidgetView = focusIv
                }

                "widget_animated_cover" -> {
                    val animContainer = FrameLayout(activity)
                    val dm = activity.resources.displayMetrics
                    val leftPx = (el.x * density).toInt()
                    // The iPod Classic layout (Classic themes) puts the cover below the full-width status bar (IpodClassicLists.menuElements).
                    val topPx = if (IpodClassicLayout.isActive) (el.y * density).toInt() else 0
                    val contW = dm.widthPixels - leftPx
                    val contH = dm.heightPixels - topPx

                    val coverLp = FrameLayout.LayoutParams(contW, contH).apply {
                        leftMargin = leftPx
                        topMargin = topPx
                    }
                    animContainer.layoutParams = coverLp
                    animContainer.clipChildren = true
                    animContainer.clipToPadding = true
                    animContainer.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    if (widgetBg != null) animContainer.background = widgetBg

                    // The same size rule as the other split screens (CoverPane)
                    val overW = com.themoon.y1.views.CoverPane.zoomed(contW)
                    val overH = com.themoon.y1.views.CoverPane.zoomed(contH)

                    val animIv = ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = FrameLayout.LayoutParams(overW, overH).apply {
                            gravity = Gravity.CENTER
                        }
                        setImageResource(R.drawable.default_album)
                    }
                    ivAnimatedCover = animIv
                    animContainer.addView(animIv)

                    startCoverPanAnimation(animIv, overW - contW, overH - contH)

                    canvas.addView(animContainer)
                    createdWidgetView = animContainer
                }
            }

            if (createdWidgetView != null) {
                widgetViewRegistry[createdWidgetView] = el
                if (!el.visibleOnFocus.isNullOrBlank()) {
                    createdWidgetView.visibility = View.GONE
                }
            }
        }

        // 2. Build Buttons
        val createdButtons = ArrayList<LinearLayout>()
        val visibleButtonElements = ArrayList<ThemeManager.MenuElement>()
        for (el in buttonElements) {
            if (!prefs.getBoolean("hide_btn_" + el.id, false)) {
                visibleButtonElements.add(el)
            }
        }

        for (i in 0 until visibleButtonElements.size) {
            val el = visibleButtonElements[i]
            val btn = LinearLayout(activity).apply {
                id = dynamicViewId(10000 + i)
                tag = el.action
                isSoundEffectsEnabled = false
                isFocusable = true
                isClickable = true
                orientation = LinearLayout.HORIZONTAL
                setOnLongClickListener(onLongPressScreenOff)
            }

            val tvMain = TextView(activity).apply {
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                minimumWidth = 0
                minimumHeight = 0
            }

            val tvRight = TextView(activity).apply {
                isSingleLine = true
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
            }

            val isIconOnly = el.textNormal.isNullOrBlank()
            val calculatedIconSize = if (isIconOnly) {
                val w = if (el.width > 0) el.width else 50
                val h = if (el.height > 0) el.height else 50
                val p = (el.padding * density).toInt()
                val tempSize = (Math.min(w, h) * density).toInt() - (p * 2)
                if (tempSize > 0) tempSize else (10 * density).toInt()
            } else {
                val h = if (el.height > 0) el.height else 50
                (h * density * 0.5f).toInt()
            }

            var textGravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            if (!el.textAlign.isNullOrEmpty()) {
                val ta = el.textAlign.lowercase(Locale.US)
                if (ta == "center") textGravity = Gravity.CENTER
                else if (ta == "right") textGravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                else if (ta == "top") textGravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                else if (ta == "bottom") textGravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            } else if (!el.gravity.isNullOrEmpty() && el.gravity.lowercase(Locale.US).contains("center")) {
                textGravity = Gravity.CENTER
            }

            if (isIconOnly) {
                btn.gravity = Gravity.CENTER
                val p = (el.padding * density).toInt()
                btn.setPadding(p, p, p, p)
                tvMain.gravity = Gravity.CENTER
            } else {
                btn.gravity = Gravity.CENTER_VERTICAL
                tvMain.gravity = textGravity
                tvRight.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL

                val customPad = (el.padding * density).toInt()
                if (el.textAlign != null && (el.textAlign.equals("top", ignoreCase = true) || el.textAlign.equals("bottom", ignoreCase = true))) {
                    val verticalPad = if (el.padding > 0) customPad else (15 * density).toInt()
                    btn.setPadding(customPad, verticalPad, customPad, verticalPad)
                } else {
                    val horizontalPad = if (el.padding > 0) customPad else (8 * density).toInt()
                    btn.setPadding(horizontalPad, customPad, horizontalPad, customPad)
                }
            }

            btn.layoutParams = createDynamicLayoutParams(el, density)
            tvMain.typeface = ThemeManager.getCustomFontBold()
            tvRight.setTypeface(ThemeManager.getCustomFont(), Typeface.NORMAL)

            if (!isIconOnly) {
                val mainSize = if (el.textSize > 0) el.textSize.toFloat() else 16f
                val rightSize = if (el.textSecondarySize > 0) el.textSecondarySize.toFloat() else mainSize
                tvMain.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, mainSize * density)
                tvRight.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, rightSize * density)
            }

            val lpMain: LinearLayout.LayoutParams
            val lpRight: LinearLayout.LayoutParams
            if (isIconOnly) {
                lpMain = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lpRight = LinearLayout.LayoutParams(0, 0)
                tvRight.visibility = View.GONE
                btn.clipChildren = false
                btn.clipToPadding = false
            } else {
                lpMain = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                lpRight = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = (10 * density).toInt()
                }
            }

            btn.addView(tvMain, lpMain)
            btn.addView(tvRight, lpRight)

            val setNormalState = Runnable {
                var normalBgColor = ThemeManager.getListButtonNormalBg()
                if (!el.bgColor.isNullOrBlank()) {
                    try {
                        normalBgColor = android.graphics.Color.parseColor(el.bgColor.trim())
                    } catch (_: Exception) {}
                }
                btn.background = createDynamicButtonBackground(normalBgColor, el.radius)

                if (isIconOnly) {
                    tvMain.text = ""
                } else {
                    tvMain.text = localize(el.textNormal ?: "")
                    tvMain.setTextColor(ThemeManager.getTextColorPrimary())
                    tvRight.text = if (el.textRight != null) localize(el.textRight) else ""
                    tvRight.visibility = View.GONE

                    if (!el.textRightColor.isNullOrEmpty()) {
                        try {
                            tvRight.setTextColor(android.graphics.Color.parseColor(el.textRightColor))
                        } catch (_: Exception) {
                            tvRight.setTextColor(ThemeManager.getTextColorPrimary())
                        }
                    } else {
                        tvRight.setTextColor(ThemeManager.getTextColorPrimary())
                    }
                }

                if (!el.iconNormal.isNullOrEmpty()) {
                    val bmp = ThemeManager.getCustomIcon(el.iconNormal, activity, 0)
                    if (bmp != null) {
                        val scaledBmp = Bitmap.createScaledBitmap(bmp, calculatedIconSize, calculatedIconSize, true)
                        val d = BitmapDrawable(activity.resources, scaledBmp).apply {
                            setBounds(0, 0, calculatedIconSize, calculatedIconSize)
                        }
                        tvMain.setCompoundDrawables(d, null, null, null)
                        tvMain.compoundDrawablePadding = if (isIconOnly) 0 else (10 * density).toInt()
                    } else {
                        tvMain.setCompoundDrawables(null, null, null, null)
                    }
                } else {
                    tvMain.setCompoundDrawables(null, null, null, null)
                }

                tvMain.translationX = 0f
                tvMain.translationY = 0f
                tvMain.scaleX = 1.0f
                tvMain.scaleY = 1.0f
            }
            setNormalState.run()

            btn.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    btn.background = if (ThemeManager.isClassicTheme()) {
                        createFocusedButtonBackground()
                    } else {
                        createDynamicButtonBackground(ThemeManager.getListButtonFocusedBg(), el.radius)
                    }

                    if (isIconOnly) {
                        tvMain.text = ""
                    } else {
                        tvMain.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                        tvRight.visibility = View.VISIBLE
                        if (!el.textRightFocusedColor.isNullOrEmpty()) {
                            try {
                                tvRight.setTextColor(android.graphics.Color.parseColor(el.textRightFocusedColor))
                            } catch (_: Exception) {
                                tvRight.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                            }
                        } else {
                            tvRight.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                        }
                        tvMain.text = if (!el.textFocused.isNullOrEmpty()) localize(el.textFocused) else localize(el.textNormal ?: "")
                    }

                    val targetIcon = if (!el.iconFocused.isNullOrEmpty()) el.iconFocused else el.iconNormal
                    if (!targetIcon.isNullOrEmpty()) {
                        val bmpF = ThemeManager.getCustomIcon(targetIcon, activity, 0)
                        if (bmpF != null) {
                            val scaledBmpF = Bitmap.createScaledBitmap(bmpF, calculatedIconSize, calculatedIconSize, true)
                            val d = BitmapDrawable(activity.resources, scaledBmpF).apply {
                                setBounds(0, 0, calculatedIconSize, calculatedIconSize)
                            }
                            tvMain.setCompoundDrawables(d, null, null, null)
                        }
                    }

                    onButtonFocusChanged(el)
                    if (isUiActive) {
                        lastMainMenuFocusIndex = i
                    }

                    tvMain.animate()
                        .translationX(el.focusOffsetX * density)
                        .translationY(el.focusOffsetY * density)
                        .scaleX(el.focusScale)
                        .scaleY(el.focusScale)
                        .setDuration(150)
                        .start()
                } else {
                    tvMain.animate()
                        .translationX(0f)
                        .translationY(0f)
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(150)
                        .start()
                    setNormalState.run()
                }
            }

            btn.setOnClickListener {
                lastMainMenuFocusIndex = i
                clickFeedback()
                onActionTriggered(el.action ?: "")
            }

            if (!el.parentId.isNullOrEmpty() && listContainers.containsKey(el.parentId)) {
                val listLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    if (el.height > 0) (el.height * density).toInt() else LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins((el.x * density).toInt(), (el.y * density).toInt(), (el.x * density).toInt(), 0)
                }
                btn.layoutParams = listLp
                listContainers[el.parentId]?.addView(btn)
            } else {
                btn.layoutParams = createDynamicLayoutParams(el, density)
                canvas.addView(btn)
            }

            createdButtons.add(btn)
        }

        currentButtons.clear()
        currentButtons.addAll(createdButtons)
        IpodClassicLists.styleMainMenuButtons(createdButtons)

        // 3. Chain Focus Navigation Loop
        val totalBtns = createdButtons.size
        val isLoopScrollOn = prefs.getBoolean("loop_scroll_on", true)
        for (i in 0 until totalBtns) {
            val currentBtn = createdButtons[i]
            val prevId = if (i == 0) (if (isLoopScrollOn) 10000 + totalBtns - 1 else View.NO_ID) else 10000 + i - 1
            val nextId = if (i == totalBtns - 1) (if (isLoopScrollOn) 10000 else View.NO_ID) else 10000 + i + 1

            currentBtn.nextFocusUpId = dynamicViewId(prevId)
            currentBtn.nextFocusLeftId = dynamicViewId(prevId)
            currentBtn.nextFocusDownId = dynamicViewId(nextId)
            currentBtn.nextFocusRightId = dynamicViewId(nextId)
        }

        // 4. Restore Focus & Scroll Position
        if (createdButtons.isNotEmpty()) {
            val targetIndex = safeMenuIndex.coerceIn(0, createdButtons.size - 1)
            val firstBtn = createdButtons[0]
            canvas.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (Build.VERSION.SDK_INT >= 16) {
                        canvas.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    } else {
                        canvas.viewTreeObserver.removeGlobalOnLayoutListener(this)
                    }

                    val targetBtn = createdButtons.getOrNull(targetIndex) ?: firstBtn
                    if (targetBtn.visibility == View.VISIBLE) {
                        val parent = targetBtn.parent
                        if (parent != null && parent.parent is ScrollView) {
                            val sv = parent.parent as ScrollView
                            val offset = (sv.height / 2) - (targetBtn.height / 2)
                            val targetY = (targetBtn.top - offset).coerceAtLeast(0)
                            sv.scrollTo(0, targetY)
                        }
                        targetBtn.requestFocus()
                        lastMainMenuFocusIndex = targetIndex
                    } else {
                        firstBtn.requestFocus()
                        val parent = firstBtn.parent
                        if (parent != null && parent.parent is ScrollView) {
                            (parent.parent as ScrollView).scrollTo(0, 0)
                        }
                        lastMainMenuFocusIndex = 0
                    }
                    isUiActive = true
                }
            })
        }
        onDynamicUiBuilt?.invoke()
    }

    private fun sortMenuElements(buttons: ArrayList<ThemeManager.MenuElement>) {
        val savedOrder = prefs.getString("custom_menu_order_" + ThemeManager.getCurrentTheme().name, "") ?: ""
        if (savedOrder.isNotEmpty()) {
            val orderList = savedOrder.split(",")
            buttons.sortWith { e1, e2 ->
                val idx1 = orderList.indexOf(e1.id)
                val idx2 = orderList.indexOf(e2.id)
                if (idx1 != -1 && idx2 != -1) idx1 - idx2
                else if (idx1 != -1) -1
                else if (idx2 != -1) 1
                else e1.focusIndex - e2.focusIndex
            }
        } else {
            buttons.sortWith { e1, e2 -> e1.focusIndex - e2.focusIndex }
        }
    }

    private fun createDynamicLayoutParams(
        el: ThemeManager.MenuElement,
        density: Float,
        extraTopOffset: Int = 0
    ): FrameLayout.LayoutParams {
        val w = if (el.width > 0) (el.width * density).toInt() else FrameLayout.LayoutParams.WRAP_CONTENT
        val h = if (el.height > 0) (el.height * density).toInt() else FrameLayout.LayoutParams.WRAP_CONTENT
        val lp = FrameLayout.LayoutParams(w, h).apply {
            gravity = parseGravity(el.gravity)
        }

        if ((lp.gravity and Gravity.RIGHT) == Gravity.RIGHT) {
            lp.rightMargin = (el.x * density).toInt()
        } else {
            lp.leftMargin = (el.x * density).toInt()
        }

        if ((lp.gravity and Gravity.BOTTOM) == Gravity.BOTTOM) {
            lp.bottomMargin = (el.y * density).toInt()
        } else {
            lp.topMargin = (el.y * density).toInt() + extraTopOffset
        }
        return lp
    }

    private fun parseGravity(gravityStr: String?): Int {
        var g = Gravity.TOP or Gravity.LEFT
        if (gravityStr.isNullOrEmpty()) return g
        val str = gravityStr.lowercase(Locale.US)
        g = 0
        if (str.contains("top")) g = g or Gravity.TOP
        if (str.contains("bottom")) g = g or Gravity.BOTTOM
        if (str.contains("center_vertical")) g = g or Gravity.CENTER_VERTICAL
        if (str.contains("left")) g = g or Gravity.LEFT
        if (str.contains("right")) g = g or Gravity.RIGHT
        if (str.contains("center_horizontal")) g = g or Gravity.CENTER_HORIZONTAL
        if (str == "center") g = Gravity.CENTER
        return if (g == 0) Gravity.TOP or Gravity.LEFT else g
    }

    private fun createDynamicButtonBackground(color: Int, elementRadius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            val r = (if (elementRadius == -1) ThemeManager.getButtonRadius() else elementRadius) *
                activity.resources.displayMetrics.density
            cornerRadius = r
        }
    }

    private fun createWidgetBackground(bgColorStr: String?, elementRadius: Int): GradientDrawable? {
        if (bgColorStr.isNullOrBlank()) return null
        val color = try {
            android.graphics.Color.parseColor(bgColorStr.trim())
        } catch (_: Exception) {
            return null
        }
        return GradientDrawable().apply {
            setColor(color)
            val r = (if (elementRadius == -1) ThemeManager.getButtonRadius() else elementRadius) *
                activity.resources.displayMetrics.density
            cornerRadius = r
        }
    }

    private fun createFocusedButtonBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF3399FF.toInt(), 0xFF0066CC.toInt())
        ).apply {
            cornerRadius = 0f
        }
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        val c = activity.currentFocus
        if (c != null) {
            val targetId = if (delta < 0) c.nextFocusUpId else c.nextFocusDownId
            if (targetId != View.NO_ID) {
                val target = activity.findViewById<View>(targetId)
                if (target != null) {
                    target.requestFocus()
                    val idx = currentButtons.indexOf(target)
                    if (idx >= 0) {
                        lastMainMenuFocusIndex = idx
                        isUiActive = true
                    }
                    clickFeedback()
                    return true
                }
            }
        } else {
            val targetIndex = lastMainMenuFocusIndex.coerceIn(0, (currentButtons.size - 1).coerceAtLeast(0))
            val targetBtn = currentButtons.getOrNull(targetIndex) ?: currentButtons.firstOrNull()
            if (targetBtn != null) {
                targetBtn.requestFocus()
                clickFeedback()
                return true
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == 21 || keyCode == 22) {
            return onWheel(if (keyCode == 22) 1 else -1, event)
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == 23 || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.repeatCount == 0) {
                event.startTracking()
            }
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == 23 || keyCode == KeyEvent.KEYCODE_ENTER) {
            if ((event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0) {
                val c = activity.currentFocus
                if (c != null) {
                    clickFeedback()
                    c.performClick()
                    return true
                }
            }
            return true
        }
        return false
    }

    override fun onBackPressed(): Boolean {
        clickFeedback()
        return true
    }
}
