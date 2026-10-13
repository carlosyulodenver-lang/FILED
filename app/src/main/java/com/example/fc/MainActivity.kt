package com.example.fc

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("fc", MODE_PRIVATE) }
    private var srcs = listOf<String>()                 // content:// URIs or absolute paths
    private lateinit var destF: LocField
    private lateinit var backF: LocField
    private var shiz: IFs? = null                       // non-null = Shizuku service connected
    private val direct by lazy { PathFs() }             // in-process path access (needs All-files access)
    private val ui = mutableListOf<Button>()
    private lateinit var sel: TextView
    private lateinit var info: TextView
    private lateinit var shizTv: TextView
    private lateinit var accTv: TextView
    private lateinit var bar: ProgressBar
    private lateinit var policySp: Spinner
    private lateinit var cancelBtn: Button
    private var curTx: Tx? = null
    private val policies = listOf("smart", "skip", "newer", "both")
    private val policyNames = listOf(
        "Smart: skip identical, replace changed", "Skip files that already exist",
        "Replace only if source is newer", "Keep both (rename the new copy)")

    private val pickFiles = registerForActivityResult(OpenMultipleDocuments()) { l -> srcs = (srcs + l.map { it.toString() }).distinct(); refresh() }
    private val pickFolder = registerForActivityResult(OpenDocumentTree()) { u -> if (u != null) { srcs = (srcs + u.toString()).distinct(); refresh() } }
    private val pickDest = registerForActivityResult(OpenDocumentTree()) { u -> if (u != null) destF.setUri(keep(u)) }
    private val pickBackup = registerForActivityResult(OpenDocumentTree()) { u -> if (u != null) backF.setUri(keep(u)) }

    // ---- Shizuku ----
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, PathFs::class.java.name)).processNameSuffix("fs").version(3)
    }
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) { shiz = IFs.Stub.asInterface(b); status() }
        override fun onServiceDisconnected(n: ComponentName) { shiz = null; status() }
    }
    private val onBinder = Shizuku.OnBinderReceivedListener { connect() }
    private val onDead = Shizuku.OnBinderDeadListener { shiz = null; status() }
    private val onPerm = Shizuku.OnRequestPermissionResultListener { _, _ -> connect() }

    private fun connect() {
        if (shiz == null && Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
            Shizuku.bindUserService(args, conn)
        status()
    }

    private fun status() = runOnUiThread {
        shizTv.text = when {
            shiz != null -> "● Shizuku: active (elevated access)"
            !Shizuku.pingBinder() -> "○ Shizuku: unavailable - using normal file access"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "○ Shizuku: tap here to grant permission"
            else -> "○ Shizuku: connecting..."
        }
        accTv.text = when {
            shiz != null -> "● Explorer & typed paths: available (via Shizuku)"
            hasAllFiles() -> "● Explorer & typed paths: available (All-files access)"
            else -> "○ Explorer & typed paths: tap here to grant All-files access"
        }
    }

    // ---- Direct path access (no Shizuku) ----
    private fun hasAllFiles() = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == 29 -> false
        else -> checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else if (Build.VERSION.SDK_INT <= 28) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        } else toast("Android 10 needs Shizuku for direct path access")
    }

    /** Path-based backend: Shizuku if connected, otherwise direct java.io with All-files access, otherwise null. */
    private fun pathFs(): IFs? = shiz ?: if (hasAllFiles()) direct else null

    // ---- UI ----
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs.getString("pending", null)?.let { promote() }   // crashed mid-operation: keep it undoable
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; setOnClickListener { a() } }
            .also { ui += it; root.addView(it) }
        fun label(t: String) = root.addView(TextView(this).apply { text = t; textSize = 16f; setPadding(0, 32, 0, 0) })
        shizTv = TextView(this).apply {
            textSize = 12f
            setOnClickListener {
                if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(1)
            }
        }
        accTv = TextView(this).apply {
            textSize = 12f; setPadding(0, 8, 0, 8)
            setOnClickListener { if (shiz == null && !hasAllFiles()) requestAllFiles() }
        }
        sel = TextView(this); info = TextView(this)
        bar = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(shizTv); root.addView(accTv)

        label("Sources")
        btn("Select Files") { pickFiles.launch(arrayOf("*/*")) }
        btn("Add Folder") { pickFolder.launch(null) }
        btn("Browse Sources (Explorer)") { explore(false, HOME) { srcs = (srcs + it).distinct(); refresh() } }
        btn("Review / Remove Sources") { reviewSources() }
        root.addView(sel)

        label("Destination folder")
        destF = LocField("dest_path", "e.g. /storage/emulated/0/Download", root) { pickDest.launch(null) }
        label("Backup / undo folder")
        backF = LocField("backup_path", "e.g. /storage/emulated/0/Backups", root) { pickBackup.launch(null) }

        label("If a file already exists")
        policySp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, policyNames)
            setSelection(prefs.getInt("policy", 0).coerceIn(0, policies.size - 1))
        }
        root.addView(policySp)

        btn("Copy") { copyClick() }
        root.addView(bar)
        cancelBtn = Button(this).apply {
            text = "Cancel copy"; visibility = View.GONE
            setOnClickListener { curTx?.cancelled = true; text = "Cancelling..." }
        }
        root.addView(cancelBtn)
        btn("Undo Last Copy") { undoClick() }
        btn("History / Undo older copies") { historyClick() }
        root.addView(info)
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(root) })
        refresh(); status()
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onDead)
        Shizuku.addRequestPermissionResultListener(onPerm)
    }

    override fun onResume() { super.onResume(); status() }   // e.g. back from the All-files access settings

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(onBinder)
        Shizuku.removeBinderDeadListener(onDead)
        Shizuku.removeRequestPermissionResultListener(onPerm)
        runCatching { Shizuku.unbindUserService(args, conn, true) }
        super.onDestroy()
    }

    /** A folder input: type a path, browse with the explorer, or use the system picker. */
    private inner class LocField(private val key: String, hint: String, root: LinearLayout, system: () -> Unit) {
        private val et = EditText(this@MainActivity).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(prefs.getString(key, ""))
        }
        private var uri: String? = null     // set when picked via the system picker
        private var shown = ""

        /** Either the picked content:// URI (if the text is untouched) or the typed path. */
        val value: String get() { val t = et.text.toString().trim(); return if (uri != null && t == shown) uri!! else t }

        init {
            root.addView(et)
            root.addView(LinearLayout(this@MainActivity).apply {
                listOf<Pair<String, () -> Unit>>(
                    "Browse folders" to { explore(true, value) { setPath(it[0]) } },
                    "System picker" to system,
                ).forEach { (t, a) ->
                    addView(Button(this@MainActivity).apply { text = t; isAllCaps = false; setOnClickListener { a() } }
                        .also { ui += it }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            })
        }

        fun setPath(p: String) { uri = null; et.setText(p) }
        fun setUri(u: Uri) { uri = u.toString(); shown = "[picker] " + friendly(uri!!); et.setText(shown) }
        fun save() { if (uri == null || et.text.toString().trim() != shown) prefs.edit().putString(key, et.text.toString().trim()).apply() }
    }

    private fun explore(folder: Boolean, start: String, onPick: (List<String>) -> Unit) {
        val fs = pathFs()
        if (fs == null) {
            AlertDialog.Builder(this).setMessage("The file explorer and typed paths need All-files access (or Shizuku).")
                .setPositiveButton("Grant") { _, _ -> requestAllFiles() }.setNegativeButton("Cancel", null).show()
            return
        }
        Explorer(this, fs, folder, if (start.startsWith("/")) normPath(start) else HOME, onPick).show()
    }

    private fun reviewSources() {
        if (srcs.isEmpty()) return toast("No sources yet")
        val items = srcs.map { friendly(it) }.toTypedArray()
        val checked = BooleanArray(items.size)
        AlertDialog.Builder(this).setTitle("Tick items to remove")
            .setMultiChoiceItems(items, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Remove selected") { _, _ -> srcs = srcs.filterIndexed { i, _ -> !checked[i] }; refresh() }
            .setNeutralButton("Clear all") { _, _ -> srcs = emptyList(); refresh() }
            .setNegativeButton("Close", null).show()
    }

    private fun friendly(l: String) = if (l.startsWith("/")) l else Uri.parse(l).lastPathSegment ?: l

    private fun refresh() {
        sel.text = "Sources: ${srcs.size} item(s)" + srcs.take(5).joinToString("") { "\n• " + friendly(it) } +
            if (srcs.size > 5) "\n…" else ""
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun keep(u: Uri): Uri {   // persist grant so Undo works after restart
        runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        return u
    }

    private suspend fun ask(msg: String) = suspendCoroutine<Boolean> { c ->
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Continue") { _, _ -> c.resume(true) }
            .setNegativeButton("Cancel") { _, _ -> c.resume(false) }
            .setOnCancelListener { c.resume(false) }.show()
    }

    // ---- History: every finished copy is kept as a journal file so it can be undone later ----
    private val histDir by lazy { File(filesDir, "history").apply { mkdirs() } }

    /** Moves a finished (or crash-interrupted) journal from prefs into the history folder. */
    private fun promote() {
        for (key in listOf("pending", "log")) prefs.getString(key, null)?.let { raw ->
            runCatching {
                val j = JSONObject(raw)
                if (j.getJSONArray("e").length() > 0)
                    File(histDir, "${j.optLong("time", System.currentTimeMillis())}.json").writeText(raw)
            }
            prefs.edit().remove(key).apply()
        }
    }

    private fun history(): List<Pair<File, JSONObject>> =
        (histDir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .sortedByDescending { it.nameWithoutExtension.toLongOrNull() ?: 0L }
            .mapNotNull { f -> runCatching { f to JSONObject(f.readText()) }.getOrNull() }

    private fun backendFor(j: JSONObject): IFs =
        if (j.getString("mode") == "saf") SafFs(this)
        else pathFs() ?: error("Shizuku or All-files access is required to undo this operation")

    private fun historyClick() {
        val h = history()
        if (h.isEmpty()) return toast("No history yet")
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val items = h.map { (_, j) ->
            df.format(Date(j.optLong("time"))) + "\n" + j.optString("summary", "Recovered (interrupted) copy") +
                "\n-> " + friendly(j.optString("dest"))
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("History (newest first)")
            .setItems(items) { _, i -> entryMenu(h, i) }
            .setNegativeButton("Close", null).show()
    }

    private fun entryMenu(h: List<Pair<File, JSONObject>>, i: Int) {
        val undoText = if (i == 0) "Undo this copy" else "Undo this and the $i newer copy/copies"
        AlertDialog.Builder(this).setTitle(h[i].second.optString("summary", "Recovered copy"))
            .setItems(arrayOf(undoText, "Delete record (keep files)", "Delete record + old backups")) { _, w ->
                when (w) { 0 -> undoUpTo(h, i); 1 -> forget(h[i], false); else -> forget(h[i], true) }
            }.setNegativeButton("Back", null).show()
    }

    /** Undoes entries newest-first down to index [i] (later copies may touch the same files, so order matters). */
    private fun undoUpTo(h: List<Pair<File, JSONObject>>, i: Int) = launchOp {
        val n = (0..i).sumOf { h[it].second.getJSONArray("e").length() }
        if (!ask("Undo ${i + 1} copy operation(s)? $n change(s) will be reverted, newest first.")) return@launchOp "Cancelled."
        for (k in 0..i) {
            val (file, j) = h[k]
            val f = backendFor(j)
            withContext(Dispatchers.IO) { undo(f, j) { file.writeText(it) } }   // progress saved, so a failed undo can resume
            file.delete()
        }
        "Undo complete."
    }

    private fun forget(e: Pair<File, JSONObject>, purge: Boolean) {
        if (!purge) { e.first.delete(); return toast("Record deleted") }
        launchOp {
            val bd = e.second.optString("bdir")
            if (bd.isEmpty()) { e.first.delete(); return@launchOp "Record deleted (this copy replaced nothing, so no backups exist)." }
            if (!ask("Permanently delete the original files this copy replaced? This copy can no longer be undone.")) return@launchOp "Cancelled."
            withContext(Dispatchers.IO) { backendFor(e.second).delete(bd) }
            e.first.delete()
            "Record and backups deleted."
        }
    }

    private fun launchOp(block: suspend () -> String) {
        bar.visibility = View.VISIBLE; ui.forEach { it.isEnabled = false }
        lifecycleScope.launch {
            info.text = try { block() } catch (e: Exception) { "Error: ${e.message}" }
            promote()
            bar.visibility = View.GONE; ui.forEach { it.isEnabled = true }
        }
    }

    // Path mode works on file paths; map SAF URIs of shared storage to paths, normalise typed paths.
    private fun toPath(l: String): String {
        if (l.startsWith("/")) return normPath(l)
        val u = Uri.parse(l)
        val id = u.lastPathSegment ?: error("Bad selection")
        val v = id.substringBefore(':'); val p = id.substringAfter(':', "")
        return normPath(when {
            v == "raw" -> p
            u.authority == "com.android.externalstorage.documents" ->
                if (v == "primary") "$HOME/$p" else "/storage/$v/$p"
            else -> error("Can't use \"${friendly(l)}\" with direct file access. Pick it with the Explorer instead.")
        })
    }

    private fun copyClick() {
        val s = srcs; val d = destF.value; val b = backF.value
        if (s.isEmpty() || d.isEmpty() || b.isEmpty()) return toast("Select files, destination and backup folder first")
        destF.save(); backF.save(); prefs.edit().putInt("policy", policySp.selectedItemPosition).apply()
        launchOp {
            val pf = pathFs()
            val ss: List<String>; val dl: String; val bl: String
            if (pf != null) {
                // drop sources that sit inside another selected folder (they are copied with it)
                ss = s.map { toPath(it) }.distinct().let { l -> l.filter { x -> l.none { t -> x.startsWith("$t/") } } }
                dl = toPath(d); bl = toPath(b)
                checkPaths(ss, dl, bl)?.let { error(it) }
                val missing = withContext(Dispatchers.IO) { listOf(dl, bl).distinct().filter { !pf.isDir(it) } }
                if (missing.isNotEmpty() && !ask("These folders don't exist and will be created:\n" + missing.joinToString("\n")))
                    return@launchOp "Cancelled."
                withContext(Dispatchers.IO) { missing.forEach { mkdirs(pf, it) } }
            } else {
                if ((s + d + b).any { it.startsWith("/") })
                    error("Typed paths and the Explorer need All-files access or Shizuku. Tap the status line above to grant it, or use the System picker buttons.")
                ss = s; dl = d; bl = b
            }
            val f: IFs = pf ?: SafFs(this)
            var total = 0L
            val tx = Tx(f, when { shiz != null -> "shizuku"; pf != null -> "path"; else -> "saf" }, bl, policies[policySp.selectedItemPosition],
                { v -> prefs.edit().apply { if (v == null) remove("pending") else putString("pending", v) }.apply() },
                { n, bytes -> runOnUiThread { info.text = "Processed $n item(s) - ${fmt(bytes)} of ${fmt(total)} copied" } })
            info.text = "Checking..."
            val plan = withContext(Dispatchers.IO) { tx.plan(ss, dl) }
            total = plan.bytes
            if (plan.add + plan.replace == 0) return@launchOp "Nothing to copy - ${plan.skip} item(s) already up to date or skipped."
            val free = withContext(Dispatchers.IO) { f.free(dl) }
            if (free > 0 && plan.bytes > free) error("Not enough free space: need ${fmt(plan.bytes)}, only ${fmt(free)} available.")
            if (plan.replace > 0 && !ask("New: ${plan.add}\nReplace: ${plan.replace} (originals moved to the backup folder first)\nSkip: ${plan.skip}\nSize: ${fmt(plan.bytes)}\n\nContinue?"))
                return@launchOp "Cancelled."
            curTx = tx; cancelBtn.text = "Cancel copy"; cancelBtn.visibility = View.VISIBLE
            try { withContext(Dispatchers.IO) { tx.run(ss, dl) } }
            finally { cancelBtn.visibility = View.GONE; curTx = null }
            val summary = "${tx.copied} copied, ${tx.replaced} replaced, ${tx.skipped} skipped (${fmt(tx.done)})"
            tx.j.put("summary", summary)
            prefs.edit().putString("pending", tx.j.toString()).apply()      // moved into History when the operation ends
            "Done: $summary."
        }
    }

    private fun undoClick() {
        val h = history()
        if (h.isEmpty()) return toast("Nothing to undo")
        undoUpTo(h, 0)
    }
}
