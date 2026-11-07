package com.v2ray.ang.ui.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityTvRailBinding
import com.v2ray.ang.ui.BaseActivity

/** Shared TV frame: a focusable action rail on the start side, a list or content pane beside it. */
abstract class TvRailActivity : BaseActivity() {
    protected lateinit var binding: ActivityTvRailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvRailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TvConnection.attach(this)
    }

    protected fun railAction(@DrawableRes icon: Int, @StringRes title: Int, onClick: () -> Unit): TextView {
        val item = LayoutInflater.from(this).inflate(R.layout.item_tv_rail, binding.rail, false) as TextView
        item.setText(title)
        if (icon != 0) item.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        item.setOnClickListener { onClick() }
        // Up/Down stop at the rail's ends; geometry alone would leak into the list beside it.
        item.id = View.generateViewId()
        val previous = binding.rail.getChildAt(binding.rail.childCount - 1)
        if (previous == null) item.nextFocusUpId = item.id else previous.nextFocusDownId = View.NO_ID
        item.nextFocusDownId = item.id
        binding.rail.addView(item)
        return item
    }

    protected fun setupList(adapter: RecyclerView.Adapter<*>) {
        binding.list.layoutManager = TvLinearLayoutManager(this)
        // Change animations replace row views, which would throw away remote focus on every update.
        binding.list.itemAnimator = null
        binding.list.adapter = adapter
    }

    protected fun showEmpty(show: Boolean, title: CharSequence = "", hint: CharSequence = "", @StringRes action: Int = 0, onAction: () -> Unit = {}) {
        binding.list.visibility = if (show) View.GONE else View.VISIBLE
        binding.empty.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        binding.emptyTitle.text = title
        binding.emptyHint.text = hint
        binding.emptyAction.visibility = if (action == 0) View.GONE else View.VISIBLE
        if (action != 0) binding.emptyAction.setText(action)
        binding.emptyAction.setOnClickListener { onAction() }
    }

    /** After a row disappears, puts focus on its neighbour rather than letting it fall anywhere. */
    protected fun focusRowAfterRemoval(position: Int) {
        val count = binding.list.adapter?.itemCount ?: 0
        if (count == 0) {
            binding.root.post {
                (if (binding.emptyAction.visibility == View.VISIBLE) binding.emptyAction else binding.rail.getChildAt(0))?.requestFocus()
            }
        } else {
            focusRow(position.coerceAtMost(count - 1))
        }
    }

    /** Waits for layout: a row scrolled to just now has no view until the list lays it out. */
    protected fun focusRow(position: Int) {
        binding.list.scrollToPosition(position)
        binding.list.doOnNextLayout {
            binding.list.findViewHolderForAdapterPosition(position)?.itemView?.findViewById<View>(R.id.row_main)?.requestFocus()
        }
        binding.list.requestLayout()
    }
}
