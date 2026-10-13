package com.example.fc

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

const val HOME = "/storage/emulated/0"

/** Storage Access Framework backend (normal, non-elevated). Locations are URI strings. */
class SafFs(private val ctx: Context) : IFs.Stub() {
    private fun f(l: String): DocumentFile {
        val u = Uri.parse(l)
        return (if (u.pathSegments.firstOrNull() == "tree") DocumentFile.fromTreeUri(ctx, u)
        else DocumentFile.fromSingleUri(ctx, u))!!
    }
    private fun mime(n: String) = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(n.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    override fun destroy() {}
    override fun isDir(l: String) = f(l).isDirectory
    override fun name(l: String) = f(l).name!!
    override fun list(d: String): Array<String> = f(d).listFiles().map { it.uri.toString() }.toTypedArray()
    override fun child(d: String, n: String): String? = f(d).findFile(n)?.uri?.toString()
    override fun stat(l: String): LongArray = f(l).let { longArrayOf(if (it.isDirectory) -1 else it.length(), it.lastModified()) }
    override fun free(d: String): Long = -1
    override fun ls(d: String): Array<String> =
        f(d).listFiles().map { (if (it.isDirectory) "d|" else "f|") + it.name }.toTypedArray()
    override fun mkdir(d: String, n: String) =
        checkNotNull(f(d).createDirectory(n)) { "Cannot create folder $n" }.uri.toString()

    override fun copy(s: String, d: String, n: String): String {
        val src = f(s)
        val t = checkNotNull(if (src.isDirectory) f(d).createDirectory(n) else f(d).createFile(mime(n), n)) { "Cannot create $n" }
        try {
            if (src.isDirectory) src.listFiles().forEach { copy(it.uri.toString(), t.uri.toString(), it.name!!) }
            else ctx.contentResolver.openInputStream(src.uri)!!.use { i ->
                ctx.contentResolver.openOutputStream(t.uri, "wt")!!.use { o -> i.copyTo(o) }
            }
        } catch (x: Exception) { t.delete(); throw x }
        return t.uri.toString()
    }

    override fun move(l: String, d: String, n: String): String {
        check(child(d, n) == null) { "$n already exists" }
        val r = copy(l, d, n)   // copy first, delete original only after copy succeeded
        delete(l)
        return r
    }

    override fun delete(l: String) { val x = f(l); check(x.delete() || !x.exists()) { "Cannot delete $l" } }
}

/** java.io backend. Also runs inside the Shizuku user service (shell/root uid). Locations are paths. */
class PathFs : IFs.Stub() {
    // Only RuntimeExceptions can cross Binder, so wrap everything.
    private fun <T> io(b: () -> T): T = try { b() } catch (e: Exception) { throw IllegalStateException(e.toString()) }

    override fun destroy() { System.exit(0) }
    override fun isDir(l: String) = File(l).isDirectory
    override fun name(l: String) = File(l).name
    // Skips symlinked folders (loop risk) and special files; fails loudly if a folder is unreadable instead of silently copying less.
    override fun list(d: String): Array<String> =
        (File(d).listFiles() ?: throw IllegalStateException("Cannot read $d"))
            .filter { it.isFile || (it.isDirectory && !Files.isSymbolicLink(it.toPath())) }
            .map { it.path }.toTypedArray()
    override fun stat(l: String): LongArray = File(l).let { longArrayOf(if (it.isDirectory) -1 else it.length(), it.lastModified()) }
    override fun free(d: String): Long {
        var f: File? = File(d)
        while (f != null && !f.exists()) f = f.parentFile
        return f?.usableSpace?.takeIf { it > 0 } ?: -1
    }
    override fun child(d: String, n: String): String? = File(d, n).takeIf { it.exists() }?.path
    override fun ls(d: String): Array<String> {
        val a = File(d).listFiles() ?: throw IllegalStateException("Cannot read $d")
        return a.map { (if (it.isDirectory) "d|" else "f|") + it.name }.toTypedArray()
    }
    override fun mkdir(d: String, n: String): String {
        val t = File(d, n); io { check(t.mkdir()) { "Cannot create $t" } }; return t.path
    }
    override fun copy(s: String, d: String, n: String): String {
        val t = File(d, n)
        io {
            check(!t.exists()) { "$n exists" }
            try { File(s).copyRecursively(t); if (File(s).isFile) t.setLastModified(File(s).lastModified()) } catch (x: Exception) { t.deleteRecursively(); throw x }
        }
        return t.path
    }
    override fun move(l: String, d: String, n: String): String {
        val t = File(d, n); val f = File(l)
        io {
            check(!t.exists()) { "$n already exists" }
            if (!f.renameTo(t)) { f.copyRecursively(t); f.deleteRecursively() }
        }
        return t.path
    }
    override fun delete(l: String) { val x = File(l); io { check(x.deleteRecursively() || !x.exists()) { "Cannot delete $l" } } }
}

private const val SKIP = 0
private const val REPLACE = 1
private const val BOTH = 2
private const val NEW = 3

/** What a copy would do (counts of files / new folders). */
class Plan { var add = 0; var replace = 0; var skip = 0; var bytes = 0L }

fun fmt(b: Long): String {
    if (b < 1024) return "$b B"
    val u = listOf("KB", "MB", "GB", "TB"); var v = b / 1024.0; var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}

/**
 * One copy operation = one transaction. Journal entries:
 *  {"t":"a","loc":X}                       X was created -> undo deletes it
 *  {"t":"b","back":B,"dir":D,"name":N}     original N in D was moved to B -> undo moves it back
 * The journal is persisted after every step; entries are undone in reverse order.
 *
 * Conflict [policy] for files that already exist (folders are always merged):
 *  smart = skip identical (same size + time), replace changed   skip = only add missing files
 *  newer = replace only if the source is newer                  both = keep both, rename the new copy
 */
class Tx(
    private val fs: IFs, mode: String, private val root: String, private val policy: String,
    private val persist: (String?) -> Unit, private val tick: (Int, Long) -> Unit,
) {
    val j: JSONObject = JSONObject().put("mode", mode).put("e", JSONArray())
    var n = 0; private set
    var copied = 0; private set
    var replaced = 0; private set
    var skipped = 0; private set
    var done = 0L; private set
    @Volatile var cancelled = false
    private var k = 0
    private var bdir: String? = null

    private fun add(o: JSONObject) { j.getJSONArray("e").put(o); persist(j.toString()) }
    private fun added(l: String) = add(JSONObject().put("t", "a").put("loc", l))

    /** Decision for source [s] meeting the existing destination item [old]. */
    private fun action(s: String, old: String): Int {
        if (fs.isDir(s) != fs.isDir(old)) return when (policy) { "both" -> BOTH; "smart" -> REPLACE; else -> SKIP }
        val a = fs.stat(s); val b = fs.stat(old)
        if (a[0] == b[0] && abs(a[1] - b[1]) <= 2000) return SKIP     // identical (2 s slack for FAT/exFAT)
        return when (policy) {
            "skip" -> SKIP
            "newer" -> if (a[1] > b[1] + 2000) REPLACE else SKIP
            "both" -> BOTH
            else -> REPLACE
        }
    }

    /** Dry run: what [run] would do. */
    fun plan(srcs: List<String>, dest: String): Plan = Plan().also { p -> srcs.forEach { scan(it, dest, p) } }

    private fun scan(s: String, dir: String?, p: Plan) {
        val old = dir?.let { fs.child(it, fs.name(s)) }
        if (fs.isDir(s)) {
            if (old != null && fs.isDir(old)) { fs.list(s).forEach { scan(it, old, p) }; return }
            if (old != null) when (action(s, old)) { SKIP -> { p.skip++; return }; REPLACE -> p.replace++ }
            p.add++
            fs.list(s).forEach { scan(it, null, p) }
        } else {
            val a = if (old == null) NEW else action(s, old)
            when (a) { SKIP -> p.skip++; REPLACE -> { p.replace++; p.bytes += fs.stat(s)[0] }; else -> { p.add++; p.bytes += fs.stat(s)[0] } }
        }
    }

    fun run(srcs: List<String>, dest: String) {
        j.put("dest", dest).put("time", System.currentTimeMillis())
        try { srcs.forEach { put(it, dest) } }
        catch (x: Exception) {
            try { undo(fs, j, persist); persist(null) }
            catch (u: Exception) { throw Exception("${x.message}. Rollback incomplete (${u.message}) - press Undo to retry.") }
            throw Exception("${x.message} - all changes rolled back.")
        }
    }

    private fun mk(dir: String, name: String) = fs.mkdir(dir, name).also { added(it); copied++ }

    private fun put(s: String, dir: String) {
        if (cancelled) throw IllegalStateException("Cancelled")
        val name = fs.name(s)
        val old = fs.child(dir, name)
        if (fs.isDir(s)) {
            val kids = fs.list(s)
            val d: String? = when {
                old == null -> mk(dir, name)
                fs.isDir(old) -> old                                   // existing folder: merge into it
                else -> when (action(s, old)) {
                    SKIP -> null
                    BOTH -> mk(dir, free(dir, name))
                    else -> { backup(old, dir, name); mk(dir, name) }
                }
            }
            if (d == null) skipped++ else kids.forEach { put(it, d) }
        } else {
            val a = if (old == null) NEW else action(s, old)
            if (a == SKIP) skipped++
            else {
                val target = when (a) {
                    BOTH -> free(dir, name)
                    REPLACE -> { backup(old!!, dir, name); name }
                    else -> name
                }
                added(fs.copy(s, dir, target)); copied++
                done += fs.stat(s)[0]
            }
        }
        tick(++n, done)
    }

    /** "photo.jpg" -> "photo (1).jpg", first name not yet taken in [dir]. */
    private fun free(dir: String, name: String): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        return generateSequence(1) { it + 1 }
            .map { name.substring(0, dot) + " ($it)" + name.substring(dot) }
            .first { fs.child(dir, it) == null }
    }

    private fun backup(old: String, dir: String, name: String) {
        val b = bdir ?: fs.mkdir(root, "undo_" + System.currentTimeMillis()).also { bdir = it; j.put("bdir", it); added(it) }
        val moved = fs.move(old, b, "${k++}_$name")                  // unique name, never overwrites
        add(JSONObject().put("t", "b").put("back", moved).put("dir", dir).put("name", name))
        replaced++
    }
}

/** Reverts a journal (reverse order). Processed entries are removed so a failed undo can be resumed. */
fun undo(fs: IFs, j: JSONObject, save: (String) -> Unit) {
    val e = j.getJSONArray("e")
    for (i in e.length() - 1 downTo 0) {
        val o = e.getJSONObject(i)
        if (o.getString("t") == "a") fs.delete(o.getString("loc"))
        else fs.move(o.getString("back"), o.getString("dir"), o.getString("name"))
        e.remove(i); save(j.toString())
    }
}

// ---- Path helpers (used for typed paths and the in-app explorer) ----

private val ALIASES = listOf("/sdcard" to HOME, "/storage/self/primary" to HOME)

/** Normalises a typed path: removes "..", "//" and trailing "/", and unifies the /sdcard aliases. */
fun normPath(p: String): String {
    var s = File(p.trim()).toPath().normalize().toString()
    for ((a, b) in ALIASES) if (s == a || s.startsWith("$a/")) s = b + s.substring(a.length)
    return s
}

/** Creates [p] and any missing parents. */
fun mkdirs(fs: IFs, p: String) {
    if (fs.isDir(p)) return
    val i = p.lastIndexOf('/')
    require(i > 0) { "Cannot create $p" }
    mkdirs(fs, p.substring(0, i))
    fs.mkdir(p.substring(0, i), p.substring(i + 1))
}

/** Guards against copying onto itself, a folder into itself, or a backup folder inside a source. */
fun checkPaths(srcs: List<String>, dest: String, backup: String): String? {
    for (s in srcs) {
        if (s == dest || s.substringBeforeLast('/') == dest) return "Destination is the same folder the source $s is already in."
        if (dest.startsWith("$s/")) return "Destination is inside the source folder $s."
        if (backup == s || backup.startsWith("$s/")) return "Backup folder is inside the source folder $s."
    }
    return null
}
