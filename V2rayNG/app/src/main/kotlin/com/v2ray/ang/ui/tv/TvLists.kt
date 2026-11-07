package com.v2ray.ang.ui.tv

import android.content.Context
import android.graphics.Rect
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Remote-friendly list behaviour: the focused row stays centered, Up/Down never jump out of the
 * list or land on an unrendered or disabled row, and a row's secondary button keeps its column.
 */
class TvLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean
    ): Boolean {
        val row = findContainingItemView(child) ?: child
        val dy = (row.top + row.bottom) / 2 - parent.height / 2
        if (dy == 0) return false
        if (immediate) parent.scrollBy(0, dy) else parent.smoothScrollBy(0, dy)
        return true
    }

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        val row = findContainingItemView(focused) ?: return null
        val step = when (direction) {
            View.FOCUS_DOWN -> 1
            View.FOCUS_UP -> -1
            else -> return null
        }
        var position = getPosition(row) + step
        while (position in 0 until itemCount) {
            val candidate = findViewByPosition(position)
            if (candidate == null) {
                scrollToPosition(position)
                return focused
            }
            // Same column if the row has one; disabled rows and headers can't take focus, so skip them.
            val target = if (focused.id != View.NO_ID) candidate.findViewById(focused.id) ?: candidate else candidate
            if (target.isFocusable && target.isEnabled) return target
            position += step
        }
        return focused
    }
}

// Tuned for filtered networks, where a healthy relay often sits at several hundred milliseconds.
private const val FAST_MS = 600L
private const val SLOW_MS = 1500L

/** Latency as a traffic light: fast, usable, or failing. */
fun latencyColorRes(delay: Long) = when {
    delay in 1 until FAST_MS -> com.v2ray.ang.R.color.tv_text_accent
    delay in FAST_MS until SLOW_MS -> com.v2ray.ang.R.color.tv_text_busy
    else -> com.v2ray.ang.R.color.tv_text_error
}

class TvOption(@StringRes val title: Int, val destructive: Boolean = false, val action: () -> Unit)

object TvDialogs {
    fun options(context: Context, title: CharSequence?, options: List<TvOption>) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(options.map { option ->
                val text = context.getString(option.title)
                if (!option.destructive) text
                else SpannableString(text).apply {
                    setSpan(ForegroundColorSpan(ContextCompat.getColor(context, com.v2ray.ang.R.color.tv_status_error)), 0, length, 0)
                }
            }.toTypedArray()) { _, which -> options[which].action() }
            .show()
    }

    fun confirm(context: Context, message: CharSequence, @StringRes confirmLabel: Int, onConfirm: () -> Unit) {
        val dialog = AlertDialog.Builder(context)
            .setMessage(message)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
            .setNegativeButton(com.v2ray.ang.R.string.tv_action_cancel, null)
            .show()
        // A stray OK press must not delete anything.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    fun input(context: Context, @StringRes title: Int, @StringRes hint: Int, onValue: (String) -> Unit) {
        val field = AppCompatEditText(context).apply {
            setHint(hint)
            isSingleLine = true
        }
        val margin = context.resources.getDimensionPixelSize(com.v2ray.ang.R.dimen.tv_space_l)
        val container = android.widget.FrameLayout(context).apply {
            setPadding(margin, margin / 2, margin, 0)
            addView(field)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> onValue(field.text.toString().trim()) }
            .setNegativeButton(com.v2ray.ang.R.string.tv_action_cancel, null)
            .show()
        field.requestFocus()
    }
}
