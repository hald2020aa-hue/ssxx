package com.openmanga.reader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.openmanga.engine.SourceConfig
import com.openmanga.engine.UrlUtil
import com.openmanga.reader.App
import com.openmanga.reader.R

class SourcesActivity : AppCompatActivity() {
    private lateinit var adapter: SimpleAdapter<SourceConfig>
    private lateinit var emptyView: android.widget.TextView

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* downloads work either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.sources_title)

        val root = FrameLayout(this)
        root.applyBottomInsets()

        adapter = SimpleAdapter(
            make = { ctx ->
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                    setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
                    isClickable = true
                    addView(ctx.label("", 17f, bold = true))
                    addView(ctx.label("", 12f))
                }
            },
            bind = { v, src, _ ->
                val box = v as LinearLayout
                (box.getChildAt(0) as android.widget.TextView).text = src.name + if (!src.enabled) "  (⏸)" else ""
                (box.getChildAt(1) as android.widget.TextView).text = (UrlUtil.host(src.baseUrl) ?: src.baseUrl) + (src.preset?.let { "  •  $it" } ?: "")
                box.alpha = if (src.enabled) 1f else 0.5f
                box.setOnClickListener {
                    if (src.enabled) startActivity(Intent(this, BrowseActivity::class.java).putExtra(BrowseActivity.EXTRA_SOURCE, src.id))
                    else toast(getString(R.string.enable))
                }
                box.setOnLongClickListener { showMenu(box, src); true }
            },
        )
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SourcesActivity)
            adapter = this@SourcesActivity.adapter
        }
        emptyView = label(getString(R.string.sources_empty), 15f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        val fab = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            contentDescription = getString(R.string.add_source)
            setOnClickListener { startActivity(Intent(this@SourcesActivity, AddSourceActivity::class.java)) }
        }
        root.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(fab, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(16), dp(16), dp(16), dp(24)) })
        setContentView(root)
        askForNotificationsOnce()
    }

    override fun onResume() {
        super.onResume()
        val all = App.instance.store.all()
        adapter.items = all
        emptyView.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showMenu(anchor: View, src: SourceConfig) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.edit_source)
            menu.add(0, 2, 1, if (src.enabled) R.string.disable else R.string.enable)
            menu.add(0, 3, 2, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> startActivity(Intent(this@SourcesActivity, AddSourceActivity::class.java).putExtra(AddSourceActivity.EXTRA_ID, src.id))
                    2 -> { App.instance.store.save(src.copy(enabled = !src.enabled)); onResume() }
                    3 -> AlertDialog.Builder(this@SourcesActivity)
                        .setMessage(getString(R.string.confirm_delete, src.name))
                        .setPositiveButton(R.string.delete) { _, _ -> App.instance.store.delete(src.id); onResume() }
                        .setNegativeButton(R.string.cancel, null).show()
                }
                true
            }
            show()
        }
    }

    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("notif_asked", false)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.notif_permission_title)
            .setMessage(R.string.notif_permission_msg)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit().putBoolean("notif_asked", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> prefs.edit().putBoolean("notif_asked", true).apply() }
            .show()
    }
}
