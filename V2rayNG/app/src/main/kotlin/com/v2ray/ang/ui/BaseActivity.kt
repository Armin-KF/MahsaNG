package com.v2ray.ang.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.v2ray.ang.R
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils

abstract class BaseActivity : AppCompatActivity() {
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (Utils.isTv(this) && supportActionBar != null) adaptPhoneLayoutToTv()
    }

    /** Screens that keep their phone layout get overscan margins and a D-pad-reachable action bar. */
    private fun adaptPhoneLayoutToTv() {
        val safeH = resources.getDimensionPixelSize(R.dimen.tv_safe_h)
        val safeV = resources.getDimensionPixelSize(R.dimen.tv_safe_v)
        val content = findViewById<View>(android.R.id.content)
        content.setPadding(safeH, 0, safeH, safeV)
        val container = findViewById<ViewGroup>(androidx.appcompat.R.id.action_bar_container)
        container?.setPadding(safeH, safeV / 2, safeH, 0)
        // AppCompat walls the action bar off from D-pad focus on the assumption that it is touched.
        container?.touchscreenBlocksFocus = false
        findViewById<ViewGroup>(androidx.appcompat.R.id.action_bar)?.touchscreenBlocksFocus = false
        if (currentFocus == null || currentFocus is ViewGroup) {
            content.getFocusables(View.FOCUS_DOWN).firstOrNull { it !is ViewGroup }?.requestFocus()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            onBackPressed()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase,  Utils.getLocale(newBase))
        }
        super.attachBaseContext(context)
    }
}
