package com.example.fc

import android.app.AlertDialog
import android.app.Dialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen file explorer working on plain paths through [fs] (Shizuku or direct access).
 * pickFolder = true: choose one folder ("Select this folder").
 * pickFolder = false: tick any number of files/folders, then "Add".
 * The path bar at the top can be edited to jump to any path.
 */
class Explorer(
    private val act: AppCompatActivity, private val fs: IFs, private val pickFolder: Boolean,
    private val start: String, private val onPick: (List<String>) -> Unit,
) {
    private var cwd = "/"
    private var rows = listOf<Pair<String, Boolean>>()      // name to isDir
    private val chosen = linkedSetOf<String>()               // absolute paths, kept across folders

    private val dlg = object : Dialog(act, androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar) {
        override fun onBackPressed() { if (cwd == "/" || cwd == HOME) dismiss() else up() }
    }
    private val pathEt = EditText(act).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_GO
    }
    private val msg = TextView(act).apply { setPadding(24, 8, 24, 8) }
    private val list = ListView(act)
    private val ok = Button(act).apply { isAllCaps = false }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(i: Int) = rows[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, v: View?, p: ViewGroup?): View {
            val row = (v as? LinearLayout) ?: LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(24, 4, 24, 4)
                addView(CheckBox(act).apply { isFocusable = false })
                addView(TextView(act).apply { textSize = 16f; setPadding(16, 28, 16, 28) })
            }
            val (n, d) = rows[i]
            (row.getChildAt(0) as CheckBox).apply {
                visibility = if (pickFolder) View.GONE else View.VISIBLE
                isChecked = join(cwd, n) in chosen
                setOnClickListener { toggle(n) }
            }
            (row.getChildAt(1) as TextView).text = if (d) "\uD83D\uDCC1 $n" else "\uD83D\uDCC4 $n"
            return row
        }
    }

    private fun join(d: String, n: String) = if (d == "/") "/$n" else "$d/$n"

    private fun toggle(n: String) {
        val p = join(cwd, n)
        if (!chosen.add(p)) chosen.remove(p)
        adapter.notifyDataSetChanged(); refreshOk()
    }

    private fun refreshOk() {
        ok.text = if (pickFolder) "Select this folder" else "Add (${chosen.size})"
        ok.isEnabled = pickFolder || chosen.isNotEmpty()
    }

    private fun selectAll() {
        val all = rows.map { join(cwd, it.first) }
        if (chosen.containsAll(all)) chosen.removeAll(all.toSet()) else chosen.addAll(all)
        adapter.notifyDataSetChanged(); refreshOk()
    }

    private fun up() { if (cwd != "/") load(cwd.substringBeforeLast('/').ifEmpty { "/" }) }

    private fun go() {
        val p = pathEt.text.toString().trim()
        if (!p.startsWith("/")) { msg.text = "Enter an absolute path starting with /"; return }
        load(normPath(p))
    }

    private fun load(p: String, fallback: String? = null) {
        act.lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { fs.ls(p) }
                rows = raw.map { it.substring(2) to (it[0] == 'd') }          // name to isDir
                    .filter { !pickFolder || it.second }
                    .sortedWith(compareBy<Pair<String, Boolean>>({ !it.second }, { it.first.lowercase() }))
                cwd = p
                pathEt.setText(p); pathEt.setSelection(p.length)
                msg.text = if (rows.isEmpty()) "(empty)" else ""
                adapter.notifyDataSetChanged(); list.setSelection(0); refreshOk()
            } catch (e: Exception) {
                if (fallback != null) load(fallback) else msg.text = "Cannot open $p: ${e.message}"
            }
        }
    }

    private fun newFolder() {
        val et = EditText(act)
        AlertDialog.Builder(act).setTitle("New folder").setView(et)
            .setPositiveButton("Create") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isEmpty() || '/' in n) { Toast.makeText(act, "Invalid name", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                act.lifecycleScope.launch {
                    try { withContext(Dispatchers.IO) { fs.mkdir(cwd, n) }; load(cwd) }
                    catch (e: Exception) { msg.text = "Cannot create folder: ${e.message}" }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    fun show() {
        fun small(t: String, a: () -> Unit) = Button(act).apply {
            text = t; isAllCaps = false; textSize = 12f; setOnClickListener { a() }
        }
        val chips = LinearLayout(act).apply {
            listOf<Pair<String, () -> Unit>>(
                "\u2B06 Up" to { up() },
                "Internal" to { load(HOME) },
                "Download" to { load("$HOME/Download") },
                "DCIM" to { load("$HOME/DCIM") },
                "Documents" to { load("$HOME/Documents") },
                "SD / USB" to { load("/storage") },
            ).forEach { (t, a) -> addView(small(t, a)) }
            if (!pickFolder) addView(small("Select all") { selectAll() })
        }
        pathEt.setOnEditorActionListener { _, _, _ -> go(); true }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, i, _ ->
            val (n, d) = rows[i]
            if (d) load(join(cwd, n)) else if (!pickFolder) toggle(n)
        }
        ok.setOnClickListener { onPick(if (pickFolder) listOf(cwd) else chosen.toList()); dlg.dismiss() }

        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        root.addView(LinearLayout(act).apply {
            addView(pathEt, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(small("Go") { go() })
        })
        root.addView(HorizontalScrollView(act).apply { addView(chips) })
        if (!pickFolder) root.addView(TextView(act).apply {
            text = "Tap a folder to open it. Tick boxes to select several - selections are kept as you browse."
            textSize = 12f; setPadding(24, 4, 24, 4)
        })
        root.addView(msg)
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(LinearLayout(act).apply {
            listOf<View>(small("New folder") { newFolder() }, ok, small("Cancel") { dlg.dismiss() })
                .forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        })
        dlg.setContentView(root)
        dlg.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
        refreshOk()
        dlg.show()
        load(start, HOME)
    }
}
