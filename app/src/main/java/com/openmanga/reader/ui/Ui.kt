package com.openmanga.reader.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import com.openmanga.engine.Diagnostic
import com.openmanga.reader.R
import com.openmanga.reader.util.Diag

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

fun Context.label(text: CharSequence, sp: Float = 14f, bold: Boolean = false, color: Int? = null): TextView = TextView(this).apply {
    this.text = text
    textSize = sp
    if (bold) setTypeface(typeface, Typeface.BOLD)
    color?.let { setTextColor(it) }
}

/** Keeps content clear of the system bars (edge-to-edge is enforced when targeting Android 15). */
fun View.applyBottomInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val b: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.updatePadding(left = b.left, right = b.right, bottom = b.bottom)
        insets
    }
}

class SimpleAdapter<T>(
    private val make: (Context) -> View,
    private val bind: (View, T, Int) -> Unit,
) : RecyclerView.Adapter<SimpleAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v)

    var items: List<T> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(make(parent.context))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: VH, position: Int) = bind(holder.itemView, items[position], position)
}

/** Shows every diagnostic; offers the in-app browser when a site asks for a browser check or login. */
fun Context.showDiagnostics(diags: List<Diagnostic>, url: String? = null) {
    val b = AlertDialog.Builder(this)
        .setTitle(R.string.details)
        .setMessage(Diag.all(diags))
        .setPositiveButton(android.R.string.ok, null)
    if (Diag.needsBrowser(diags) && url != null) {
        b.setNeutralButton(R.string.open_in_browser) { _, _ ->
            startActivity(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url))
        }
    }
    b.show()
}
