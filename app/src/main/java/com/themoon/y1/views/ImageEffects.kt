package com.themoon.y1.views

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur

object ImageEffects {

    fun applyGaussianBlur(context: Context, original: Bitmap?): Bitmap? {
        if (original == null) return null
        return try {
            var w = Math.round(original.width * 0.2f)
            var h = Math.round(original.height * 0.2f)
            if (w < 1) w = 1
            if (h < 1) h = 1
            val scaled = Bitmap.createScaledBitmap(original, w, h, false)
            val output = Bitmap.createBitmap(scaled)

            val rs = RenderScript.create(context)
            val intrinsic = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            val tmpIn = Allocation.createFromBitmap(rs, scaled)
            val tmpOut = Allocation.createFromBitmap(rs, output)
            intrinsic.setRadius(25f)
            intrinsic.setInput(tmpIn)
            intrinsic.forEach(tmpOut)
            tmpOut.copyTo(output)
            rs.destroy()
            output
        } catch (_: Exception) {
            original
        }
    }
}
