package com.gpod.player

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

// ---------------------------------------------------------------- Preferences

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("gpod", Context.MODE_PRIVATE)

    var repeatMode: Int
        get() = sp.getInt("repeat", Player.REPEAT_MODE_OFF)
        set(v) = sp.edit().putInt("repeat", v).apply()
    var shuffle: Boolean
        get() = sp.getBoolean("shuffle", false)
        set(v) = sp.edit().putBoolean("shuffle", v).apply()

    /** 0 off, 1 light, 2 medium, 3 strong */
    var haptics: Int
        get() = sp.getInt("haptics", 2)
        set(v) = sp.edit().putInt("haptics", v).apply()
    var clicker: Boolean
        get() = sp.getBoolean("clicker", true)
        set(v) = sp.edit().putBoolean("clicker", v).apply()

    /** 0 silver, 1 black, 2 space gray */
    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()

    /** Music lists also shown on the main menu, by label (e.g. "Artists"). */
    var mainMenuLists: Set<String>
        get() = sp.getStringSet("mainMenuLists", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("mainMenuLists", v.toSet()).apply()

    /** Smart playlists (Recently Added, …) the user turned off. */
    var hiddenSmartLists: Set<String>
        get() = sp.getStringSet("hiddenSmartLists", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("hiddenSmartLists", v.toSet()).apply()

    var squareCorners: Boolean
        get() = sp.getBoolean("squareCorners", false)
        set(v) = sp.edit().putBoolean("squareCorners", v).apply()

    var hideNavBar: Boolean
        get() = sp.getBoolean("hideNavBar", false)
        set(v) = sp.edit().putBoolean("hideNavBar", v).apply()

    /** 0 slow, 1 normal, 2 fast */
    var wheelSpeed: Int
        get() = sp.getInt("wheelSpeed", 1)
        set(v) = sp.edit().putInt("wheelSpeed", v).apply()

    /** Folders (as shown in Settings > Music Folders) whose songs are hidden. */
    var excludedFolders: Set<String>
        get() = sp.getStringSet("excludedFolders", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("excludedFolders", v.toSet()).apply()

    var queue: List<String>
        get() = sp.getString("queue", "")!!.split('\n').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("queue", v.joinToString("\n")).apply()
    var queueCurrent: String
        get() = sp.getString("queueCurrent", "")!!
        set(v) = sp.edit().putString("queueCurrent", v).apply()
    var queuePosition: Long
        get() = sp.getLong("queuePos", 0L)
        set(v) = sp.edit().putLong("queuePos", v).apply()
}

// ---------------------------------------------------------------- GPod playlists (editable)

data class UserPlaylist(val id: Long, val name: String, val songs: List<String>)

/** Playlists owned by GPod: named, renamed and edited in the app. */
class PlaylistStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("gpod_playlists", Context.MODE_PRIVATE)
    private var cache: List<UserPlaylist>? = null

    fun all(): List<UserPlaylist> = cache ?: read().also { cache = it }

    fun get(id: Long) = all().firstOrNull { it.id == id }

    fun create(name: String, songs: List<String>): UserPlaylist {
        val id = maxOf(System.currentTimeMillis(), (all().maxOfOrNull { it.id } ?: 0L) + 1)
        val p = UserPlaylist(id, name, songs.distinct())
        write(all() + p)
        return p
    }

    fun update(id: Long, f: (UserPlaylist) -> UserPlaylist) = write(all().map { if (it.id == id) f(it) else it })

    fun delete(id: Long) = write(all().filter { it.id != id })

    private fun read(): List<UserPlaylist> = try {
        val arr = JSONArray(sp.getString("lists", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val keys = o.getJSONArray("songs")
            UserPlaylist(o.getLong("id"), o.getString("name"), (0 until keys.length()).map { keys.getString(it) })
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(list: List<UserPlaylist>) {
        cache = list
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("songs", JSONArray(p.songs)))
        }
        // commit(), not apply(): playlist edits must survive the app being killed right after.
        sp.edit().putString("lists", arr.toString()).commit()
    }
}

// ---------------------------------------------------------------- Skins

data class Skin(
    val bodyTop: Int, val bodyBottom: Int,
    val wheelTop: Int, val wheelBottom: Int, val wheelEdge: Int,
    val label: Int, val pressed: Int,
    val centerTop: Int, val centerBottom: Int, val centerEdge: Int,
    val bezel: Int, val lightBars: Boolean,
) {
    companion object {
        val SILVER = Skin(
            0xFFEEEEF0.toInt(), 0xFFC3C4C8.toInt(),
            0xFFFDFDFD.toInt(), 0xFFE6E6E8.toInt(), 0xFFB4B5BA.toInt(),
            0xFFA0A1A7.toInt(), 0x22000000,
            0xFFE6E7EA.toInt(), 0xFFC6C7CB.toInt(), 0xFFA9AAAF.toInt(),
            0xFF1E1E20.toInt(), true,
        )
        /** Pure black for AMOLED: only the thin wheel outlines and labels light up. */
        val BLACK = Skin(
            0xFF000000.toInt(), 0xFF000000.toInt(),
            0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF2E2E33.toInt(),
            0xFFA6A7AD.toInt(), 0x26FFFFFF,
            0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF2E2E33.toInt(),
            0xFF000000.toInt(), false,
        )

        /** iPod classic gunmetal: anodised grey face, near-black wheel, face-coloured center button. */
        val SPACE_GRAY = Skin(
            0xFF606166.toInt(), 0xFF3A3B3F.toInt(),
            0xFF2B2B2E.toInt(), 0xFF1A1A1C.toInt(), 0xFF111113.toInt(),
            0xFFB2B3B8.toInt(), 0x22FFFFFF,
            0xFF5C5D62.toInt(), 0xFF3F4044.toInt(), 0xFF1E1E21.toInt(),
            0xFF000000.toInt(), false,
        )

        // Stored values: 0 Silver, 1 Black, 2 Space Gray (kept stable for existing settings).
        fun forTheme(t: Int) = when (t) { 1 -> BLACK; 2 -> SPACE_GRAY; else -> SILVER }
        fun name(t: Int) = when (t) { 1 -> "Black"; 2 -> "Space Gray"; else -> "Silver" }
        /** Settings order: Silver -> Space Gray -> Black. */
        fun next(t: Int) = when (t) { 0 -> 2; 2 -> 1; else -> 0 }
    }
}

// ---------------------------------------------------------------- Library

data class Song(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val track: Int,
    val duration: Long,
    val genre: String,
    val folder: String,
    val dateAdded: Long,
) {
    val key: String get() = uri.toString()

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(key)
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setTrackNumber(track % 1000)
                .build()
        )
        .build()
}

class Album(val id: Long, val title: String, val artist: String, val songs: List<Song>)

/** Sort key that ignores case and a leading "The ", like the iPod. */
fun sortKey(s: String): String {
    val l = s.trim().lowercase()
    return if (l.startsWith("the ")) l.substring(4) else l
}

private val trackOrder = compareBy<Song>({ it.track }, { sortKey(it.title) })

class Library(val songs: List<Song>, val rawPlaylists: List<Pair<String, List<Long>>> = emptyList()) {
    val byKey: Map<String, Song> = songs.associateBy { it.key }
    private val byId: Map<Long, Song> = songs.associateBy { it.id }

    /** Playlists from the device (MediaStore, including scanned .m3u files). */
    val playlists: List<Pair<String, List<Song>>> = rawPlaylists
        .map { (name, ids) -> name to ids.mapNotNull { byId[it] } }
        .sortedBy { sortKey(it.first) }

    val recentlyAdded: List<Song> = songs.sortedByDescending { it.dateAdded }.take(100)

    val songsByTitle: List<Song> = songs.sortedBy { sortKey(it.title) }

    val albums: List<Album> = songs.groupBy { it.albumId }.map { (id, list) ->
        val artists = list.map { it.artist }.distinct()
        Album(id, list.first().album, if (artists.size == 1) artists[0] else "Various Artists", list.sortedWith(trackOrder))
    }.sortedBy { sortKey(it.title) }

    /** Cover Flow order: by artist, then album, like the iPod. */
    val albumsByArtist: List<Album> = albums.sortedWith(compareBy({ sortKey(it.artist) }, { sortKey(it.title) }))

    val artists: List<Pair<String, List<Song>>> = groupSorted { it.artist }
    val genres: List<Pair<String, List<Song>>> = groupSorted { it.genre }
    /** Sorted by the folder's own name (what the Folders menu shows), so letter jumps line up. */
    val folders: List<Pair<String, List<Song>>> = groupSorted { it.folder }
        .sortedBy { sortKey(it.first.substringAfterLast('/')) }

    private fun groupSorted(by: (Song) -> String) = songs.groupBy(by)
        .map { (k, v) -> k to v.sortedWith(compareBy<Song>({ sortKey(it.album) }, { it.track }, { sortKey(it.title) })) }
        .sortedBy { sortKey(it.first) }

    companion object {
        fun load(ctx: Context): Library {
            val col = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val proj = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.GENRE,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.VOLUME_NAME,
            )
            val out = ArrayList<Song>()
            ctx.contentResolver.query(col, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    fun str(i: Int, def: String): String {
                        val s = c.getString(i)
                        return if (s.isNullOrBlank() || s == "<unknown>") def else s.trim()
                    }
                    out += Song(
                        id = id,
                        uri = ContentUris.withAppendedId(col, id),
                        title = str(1, str(9, "Unknown").substringBeforeLast('.')),
                        artist = str(2, "Unknown Artist"),
                        album = str(3, "Unknown Album"),
                        albumId = c.getLong(4),
                        track = c.getInt(5),
                        duration = c.getLong(6),
                        genre = str(7, "Unknown Genre"),
                        folder = folderKey(c.getString(11), c.getString(8)),
                        dateAdded = c.getLong(10),
                    )
                }
            }
            return Library(out, loadPlaylists(ctx))
        }

        /** "Music/Rock" on the phone, "SD Card/Music/Rock" on a memory card. */
        private fun folderKey(volume: String?, relPath: String?): String {
            val rel = relPath.orEmpty().trim('/')
            val base = if (volume == null || volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) "" else "SD Card"
            return listOf(base, rel).filter { it.isNotEmpty() }.joinToString("/").ifEmpty { "Storage Root" }
        }

        @Suppress("DEPRECATION")
        private fun loadPlaylists(ctx: Context): List<Pair<String, List<Long>>> {
            val out = ArrayList<Pair<String, List<Long>>>()
            try {
                val lists = MediaStore.Audio.Playlists.getContentUri(MediaStore.VOLUME_EXTERNAL)
                ctx.contentResolver.query(
                    lists, arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME), null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        val members = ArrayList<Long>()
                        ctx.contentResolver.query(
                            MediaStore.Audio.Playlists.Members.getContentUri(MediaStore.VOLUME_EXTERNAL, id),
                            arrayOf(MediaStore.Audio.Playlists.Members.AUDIO_ID),
                            null, null, MediaStore.Audio.Playlists.Members.PLAY_ORDER,
                        )?.use { m -> while (m.moveToNext()) members += m.getLong(0) }
                        out += name to members
                    }
                }
            } catch (e: Exception) {
                // Some devices restrict playlist access; just show none.
            }
            return out
        }
    }
}

// ---------------------------------------------------------------- Album art

class ArtCache(private val ctx: Context, private val onLoaded: () -> Unit) {
    private val cache = LruCache<Long, Bitmap>(36)
    private val missing = HashSet<Long>()
    private val pending = HashSet<Long>()
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun get(song: Song): Bitmap? {
        val id = song.albumId
        cache.get(id)?.let { return it }
        if (id in missing || id in pending) return null
        pending += id
        exec.execute {
            val bmp = try {
                ctx.contentResolver.loadThumbnail(song.uri, Size(480, 480), null)
            } catch (e: Exception) {
                null
            }
            main.post {
                pending -= id
                if (bmp != null) cache.put(id, bmp) else missing += id
                onLoaded()
            }
        }
        return null
    }

    fun clear() {
        cache.evictAll(); missing.clear()
    }
}

// ---------------------------------------------------------------- Haptics + clicker

class Feedback(ctx: Context, private val prefs: Prefs) {
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    private val tickEffects = arrayOf(
        null,
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK),
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
        VibrationEffect.createOneShot(14, 255),
    )
    private val pressEffects = arrayOf(
        null,
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK),
        VibrationEffect.createOneShot(30, 255),
    )

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()
    private val clickId = pool.load(makeClickWav(ctx).path, 1)

    fun tick() {
        tickEffects.getOrNull(prefs.haptics)?.let { vibrator.vibrate(it) }
        if (prefs.clicker) pool.play(clickId, 0.5f, 0.5f, 1, 0, 1f)
    }

    fun press() {
        pressEffects.getOrNull(prefs.haptics)?.let { vibrator.vibrate(it) }
        if (prefs.clicker) pool.play(clickId, 0.8f, 0.8f, 1, 0, 0.8f)
    }

    /** Synthesises a short iPod-style "tick" so we ship no binary assets. */
    private fun makeClickWav(ctx: Context): File {
        val f = File(ctx.cacheDir, "click_v1.wav")
        if (f.exists()) return f
        val rate = 44100
        val n = (rate * 0.012).toInt()
        val rnd = Random(7)
        val buf = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = exp(-t / 0.0009)
            val v = env * (0.6 * sin(2 * PI * 3200 * t) + 0.4 * (rnd.nextDouble() * 2 - 1))
            buf.putShort((v * 0.9 * Short.MAX_VALUE).toInt().toShort())
        }
        f.writeBytes(buf.array())
        return f
    }
}
