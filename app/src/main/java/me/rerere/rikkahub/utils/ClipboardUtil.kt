package me.rerere.rikkahub.utils

import android.content.ClipData

import android.content.Context

fun ClipData.getText(): String {
    return buildString {
        repeat(itemCount) {
            append(getItemAt(it).text ?: "")
        }
    }
}

fun Context.copyToClipboard(text: String) {
    val clip = android.content.ClipData.newPlainText("label", text)
    getSystemService(Context.CLIPBOARD_SERVICE)?.let {
        (it as android.content.ClipboardManager).setPrimaryClip(clip)
    }
}
