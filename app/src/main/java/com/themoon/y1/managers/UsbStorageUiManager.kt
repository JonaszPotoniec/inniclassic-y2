package com.themoon.y1.managers

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.managers.UsbMassStorageController
import com.themoon.y1.views.TablerIcons

class UsbStorageUiManager(
    private val activity: Activity,
    private val getExternalSdMountMonitor: () -> ExternalSdMountMonitor?,
    private val isFmRadioActive: () -> Boolean,
    private val stopFmRadio: () -> Unit,
    private val localize: (String) -> String
) {
    var usbStorageOverlay: View? = null
        private set
    var usbStorageBusy: Boolean = false
        private set

    val isStorageOverlayActive: Boolean
        get() = usbStorageOverlay != null

    fun showUsbStorageDialog() {
        AlertDialog.Builder(activity)
            .setTitle(localize("USB Connected"))
            .setMessage(localize("Enable USB Mass Storage mode?"))
            .setPositiveButton(localize("Enable")) { _, _ -> startUsbStorage() }
            .setNegativeButton(localize("Cancel"), null)
            .show()
    }

    fun startUsbStorage() {
        if (usbStorageBusy) return
        usbStorageBusy = true
        val am = AudioPlayerManager.getInstance()
        if (am.isPlaying()) am.playOrPauseMusic()
        if (isFmRadioActive()) stopFmRadio()
        getExternalSdMountMonitor()?.setSuspended(true)
        showUsbStorageOverlay()
        Thread({
            UsbMassStorageController.setEnabled(true)
            usbStorageBusy = false
        }, "y1-start-ums").start()
    }

    fun stopUsbStorage() {
        if (usbStorageBusy) return
        usbStorageBusy = true
        Thread({
            UsbMassStorageController.setEnabled(false)
            getExternalSdMountMonitor()?.setSuspended(false)
            activity.runOnUiThread {
                hideUsbStorageOverlay()
                usbStorageBusy = false
            }
        }, "y1-stop-ums").start()
    }

    fun showUsbStorageOverlay() {
        if (usbStorageOverlay != null) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val d = activity.resources.displayMetrics.density
        val overlay = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF000000.toInt())
            isClickable = true
            isFocusable = true

            val iv = ImageView(activity).apply {
                setImageBitmap(TablerIcons.render(TablerIcons.USB, (64f * d).toInt(), Color.WHITE))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (24f * d).toInt()
                }
            }
            addView(iv)

            val tvTitle = TextView(activity).apply {
                text = localize("Do not disconnect")
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
            }
            addView(tvTitle)

            val tvSub = TextView(activity).apply {
                text = localize("Press CENTER to disconnect")
                setTextColor(0xFF888888.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, (12f * d).toInt(), 0, 0)
            }
            addView(tvSub)
        }
        usbStorageOverlay = overlay
        root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun hideUsbStorageOverlay() {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        usbStorageOverlay?.let { root?.removeView(it) }
        usbStorageOverlay = null
    }
}
