package com.themoon.y1.managers

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.ThemeManager
import com.themoon.y1.views.TablerIcons

class UsbStorageUiManager(
    private val activity: Activity,
    private val getExternalSdMountMonitor: () -> ExternalSdMountMonitor?,
    private val isFmRadioActive: () -> Boolean,
    private val stopFmRadio: () -> Unit,
    private val localize: (String) -> String,
    private val clickFeedback: () -> Unit,
    private val createListButtonWithIcon: (iconUnicode: String, textLabel: String) -> View
) {
    var usbStorageOverlay: View? = null
        private set
    var usbStorageBusy: Boolean = false
        private set
    var usbStorageDialog: Dialog? = null
        private set
    var lastUsbDialogShownMs: Long = 0L

    val isStorageOverlayActive: Boolean
        get() = usbStorageOverlay != null

    val isDialogShowing: Boolean
        get() = usbStorageDialog != null && usbStorageDialog?.isShowing == true

    fun dismissUsbStorageDialog() {
        usbStorageDialog?.let {
            try { it.dismiss() } catch (_: Exception) {}
        }
        usbStorageDialog = null
    }

    fun showUsbStorageDialog() {
        if (usbStorageDialog != null) {
            try { usbStorageDialog?.dismiss() } catch (_: Exception) {}
            usbStorageDialog = null
        }
        val dialog = Dialog(activity)
        usbStorageDialog = dialog
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener {
            lastUsbDialogShownMs = System.currentTimeMillis()
            if (usbStorageDialog === dialog) {
                usbStorageDialog = null
            }
        }
        dialog.setOnCancelListener {
            lastUsbDialogShownMs = System.currentTimeMillis()
        }

        val d = activity.resources.displayMetrics.density

        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                val baseBg = ThemeManager.getOverlayBackgroundColor() or 0xCC000000.toInt()
                setColor(baseBg)
                cornerRadius = 15f * d
                val strokeColor = if (ThemeManager.isClassicLightTheme()) 0x33000000 else 0x33FFFFFF
                setStroke((1f * d).toInt(), strokeColor)
            }
            background = bg
            setPadding((15f * d).toInt(), (20f * d).toInt(), (15f * d).toInt(), (15f * d).toInt())
        }

        val iconSizePx = (36f * d).toInt()
        val ivUsb = ImageView(activity).apply {
            setImageBitmap(TablerIcons.render(TablerIcons.USB, iconSizePx, ThemeManager.getTextColorPrimary()))
            layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (8f * d).toInt()
            }
        }
        rootLayout.addView(ivUsb)

        val tvTitle = TextView(activity).apply {
            text = localize("USB Connected")
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 17f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (6f * d).toInt())
        }
        rootLayout.addView(tvTitle)

        val tvMsg = TextView(activity).apply {
            text = localize("Connect this device as USB storage to transfer files to your computer?")
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 13f
            typeface = ThemeManager.getCustomFont()
            gravity = Gravity.CENTER
            setPadding((4f * d).toInt(), 0, (4f * d).toInt(), (18f * d).toInt())
            setLineSpacing(0f, 1.3f)
        }
        rootLayout.addView(tvMsg)

        val dialogWheelListener = View.OnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == 19 || keyCode == 21) {
                    val idx = rootLayout.indexOfChild(v)
                    for (i in idx - 1 downTo 0) {
                        val child = rootLayout.getChildAt(i)
                        if (child.isFocusable) {
                            child.requestFocus()
                            clickFeedback()
                            return@OnKeyListener true
                        }
                    }
                    return@OnKeyListener true
                }
                if (keyCode == 20 || keyCode == 22) {
                    val idx = rootLayout.indexOfChild(v)
                    for (i in idx + 1 until rootLayout.childCount) {
                        val child = rootLayout.getChildAt(i)
                        if (child.isFocusable) {
                            child.requestFocus()
                            clickFeedback()
                            return@OnKeyListener true
                        }
                    }
                    return@OnKeyListener true
                }
            }
            false
        }

        val btnConnect = createListButtonWithIcon("", localize("Connect as Storage")).apply {
            isFocusable = true
            setOnKeyListener(dialogWheelListener)
            setOnClickListener {
                clickFeedback()
                dialog.dismiss()
                startUsbStorage()
            }
        }
        rootLayout.addView(btnConnect)

        val btnCharge = createListButtonWithIcon("", localize("Charge Only")).apply {
            isFocusable = true
            setOnKeyListener(dialogWheelListener)
            setOnClickListener {
                clickFeedback()
                lastUsbDialogShownMs = System.currentTimeMillis()
                dialog.dismiss()
            }
        }
        rootLayout.addView(btnCharge)

        dialog.setContentView(rootLayout)
        dialog.window?.let { window ->
            window.setLayout((300f * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()

        rootLayout.postDelayed({
            if (rootLayout.childCount > 0) {
                for (i in 0 until rootLayout.childCount) {
                    val child = rootLayout.getChildAt(i)
                    if (child.isFocusable) {
                        child.requestFocus()
                        break
                    }
                }
            }
        }, 60)
    }

    private fun pauseAllPlaybackForUsb() {
        try {
            val am = AudioPlayerManager.getInstance()
            if (am.isPlaying()) {
                am.playOrPauseMusic()
            }
        } catch (_: Exception) {}
        try {
            val vm = VideoPlayerManager.getInstance()
            if (vm.isPlaying()) {
                vm.togglePlayPause()
            }
        } catch (_: Exception) {}
        try {
            if (isFmRadioActive()) {
                stopFmRadio()
            }
        } catch (_: Exception) {}
    }

    fun startUsbStorage() {
        if (usbStorageBusy) return
        if (MediaLibraryManager.getInstance().isScanning) {
            // The scan keeps files open; vold would kill this app to unmount the volume.
            Toast.makeText(activity, localize("Wait for the library scan to finish, then try again."), Toast.LENGTH_LONG).show()
            return
        }
        usbStorageBusy = true
        pauseAllPlaybackForUsb()
        getExternalSdMountMonitor()?.setSuspended(true)
        Thread({
            UsbMassStorageController.ensureMassStorageFunction()
            val ok = UsbMassStorageController.setEnabled(true)
            if (!ok) {
                UsbMassStorageController.restoreUsbFunction()
            }
            activity.runOnUiThread {
                usbStorageBusy = false
                if (ok) {
                    showUsbStorageOverlay()
                } else {
                    // The function switch reconnects USB; do not ask again at once.
                    lastUsbDialogShownMs = System.currentTimeMillis()
                    getExternalSdMountMonitor()?.setSuspended(false)
                    Toast.makeText(activity, localize("Couldn't connect as USB storage."), Toast.LENGTH_LONG).show()
                }
            }
        }, "y1-ums-on").start()
    }

    fun stopUsbStorage() {
        if (usbStorageBusy) return
        usbStorageBusy = true
        Thread({
            val stillOn = UsbMassStorageController.setEnabled(false)
            if (!stillOn) {
                UsbMassStorageController.restoreUsbFunction()
            }
            activity.runOnUiThread {
                usbStorageBusy = false
                if (stillOn) {
                    Toast.makeText(activity, localize("Couldn't eject USB storage."), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                // The function switch reconnects USB; do not ask again at once.
                lastUsbDialogShownMs = System.currentTimeMillis()
                hideUsbStorageOverlay()
                getExternalSdMountMonitor()?.setSuspended(false)
            }
        }, "y1-ums-off").start()
    }

    fun onUsbCableRemovedWhileShared() {
        Handler(Looper.getMainLooper()).postDelayed({
            Thread({
                if (UsbMassStorageController.isEnabled()) {
                    activity.runOnUiThread { stopUsbStorage() }
                } else {
                    activity.runOnUiThread {
                        hideUsbStorageOverlay()
                        getExternalSdMountMonitor()?.setSuspended(false)
                    }
                }
            }, "y1-ums-unplug").start()
        }, 1500)
    }

    fun showUsbStorageOverlay() {
        if (usbStorageOverlay != null) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val d = activity.resources.displayMetrics.density

        val overlay = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            setBackgroundColor(ThemeManager.getOverlayBackgroundColor() or 0xFF000000.toInt())
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = (24 * d).toInt()
                rightMargin = (24 * d).toInt()
            }
        }

        val iconSizePx = (96 * d).toInt()
        val iv = ImageView(activity).apply {
            setImageBitmap(TablerIcons.render(TablerIcons.USB, iconSizePx, ThemeManager.getTextColorPrimary()))
            layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * d).toInt()
            }
        }
        content.addView(iv)

        val tvTitle = TextView(activity).apply {
            text = localize("Connected")
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 22f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (6 * d).toInt())
        }
        content.addView(tvTitle)

        val tvSubtitle = TextView(activity).apply {
            text = localize("Eject before disconnecting.")
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 14f
            typeface = ThemeManager.getCustomFont()
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (24 * d).toInt())
        }
        content.addView(tvSubtitle)

        val btnEject = createListButtonWithIcon("", localize("Eject")).apply {
            layoutParams = LinearLayout.LayoutParams(
                (180 * d).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener {
                clickFeedback()
                stopUsbStorage()
            }
        }
        content.addView(btnEject)

        overlay.addView(content)
        root.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        usbStorageOverlay = overlay

        try {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}

        btnEject.postDelayed({ btnEject.requestFocus() }, 60)
    }

    fun hideUsbStorageOverlay() {
        if (usbStorageOverlay != null) {
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            root?.removeView(usbStorageOverlay)
            usbStorageOverlay = null
        }
        try {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}
    }
}
