package com.themoon.y1

/** The device model, for the code that is only for one device. */
object DeviceModel {
    /** True on the Y2 ([model] is `Build.MODEL`). */
    fun isY2(model: String?): Boolean = model?.trim() == "Y2"
}
