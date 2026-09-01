package top.niunaijun.blackboxa.bean

import android.graphics.drawable.Drawable

/**
 * “禁用 libc hook”页面的条目：勾选后对该应用关闭 so 的 GOT hook
 */
data class LibcHookAppInfo(
        val name: String,
        val packageName: String,
        var disabled: Boolean,
        val icon: Drawable
)
