package com.gpod.player

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import java.util.concurrent.Executors
import kotlin.random.Random

class MainActivity : ComponentActivity(), ClickWheelView.Listener {

    lateinit var prefs: Prefs
    lateinit var screen: PodScreenView
    lateinit var art: ArtCache
    lateinit var userPlaylists: PlaylistStore
    private lateinit var wheel: ClickWheelView
    private lateinit var root: LinearLayout
    /** Black backdrop; whatever shows below [root] (the navigation bar area) is black. */
    private lateinit var frame: FrameLayout
    private lateinit var feedback: Feedback
    private lateinit var audio: AudioManager

    var controller: MediaController? = null
        private set
    /** What's shown and played: the scan minus any folders turned off in Settings. */
    var library: Library? = null
        private set
    /** Everything found on the phone, before folder filtering. */
    private var fullLibrary: Library? = null
    /** Every folder that directly contains music, e.g. "Music/Rock" or "SD Card/Music". */
    var allFolders: List<String> = emptyList()
        private set

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var askedPermission = false
    private var restored = false

    private val audioPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) loadLibrary() else screen.invalidate()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        deleteSharedPreferences("gpod_stats") // old play counts / history from earlier versions
        userPlaylists = PlaylistStore(this)
        feedback = Feedback(this, prefs)
        audio = getSystemService(AudioManager::class.java)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val density = resources.displayMetrics.density
        screen = PodScreenView(this).apply {
            onTapHandled = { feedback.press() }
        }
        wheel = ClickWheelView(this).apply { listener = this@MainActivity }
        art = ArtCache(this) { screen.invalidate() }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(screen, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                val m = (18 * density).toInt()
                setMargins(m, (22 * density).toInt(), m, 0)
            })
            addView(wheel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        frame = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(bars.left, bars.top, bars.right, 0)
            // Leave the navigation bar's strip uncovered so the black frame shows behind it.
            (root.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom
            root.requestLayout()
            insets
        }
        setContentView(frame)
        applyPrefs()

        screen.setRoot(Menus.root(this))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!screen.top.onMenu() && !screen.pop()) moveTaskToBack(true)
            }
        })

        if (hasPermission()) loadLibrary() else requestPermission()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            val c = try { future.get() } catch (e: Exception) { return@addListener }
            controller = c
            c.addListener(playerListener)
            syncPlayerState()
            maybeRestoreQueue()
            screen.refreshAll()
        }, ContextCompat.getMainExecutor(this))
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        main.post(ticker)
    }

    override fun onStop() {
        saveQueue()
        main.removeCallbacks(ticker)
        main.removeCallbacks(seekRunnable)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted access from system settings.
        if (library == null && hasPermission()) loadLibrary()
    }

    // ------------------------------------------------------------------ settings / skin

    fun applyPrefs() {
        val skin = Skin.forTheme(prefs.theme)
        wheel.skin = skin
        screen.roundedCorners = !prefs.squareCorners
        wheel.ticksPerRev = intArrayOf(16, 22, 30)[prefs.wheelSpeed]
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(skin.bodyTop, skin.bodyBottom))
        window.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(skin.bodyTop, skin.bodyBottom)))
        applySystemBars()
    }

    private fun applySystemBars() {
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, frame).apply {
            isAppearanceLightStatusBars = Skin.forTheme(prefs.theme).lightBars
            isAppearanceLightNavigationBars = false // light buttons on the black strip
            if (prefs.hideNavBar) {
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                show(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs (e.g. the keyboard prompt) can bring the bar back; re-hide when we regain focus.
        if (hasFocus) applySystemBars()
    }

    // ------------------------------------------------------------------ permission + library

    fun hasPermission() = ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED

    fun requestPermission() {
        if (askedPermission && !shouldShowRequestPermissionRationale(audioPermission)) {
            // Permanently denied: send the user to app settings.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            askedPermission = true
            permLauncher.launch(audioPermission)
        }
    }

    fun loadLibrary(announce: Boolean = false) {
        if (!hasPermission()) return
        if (announce) screen.flash("Scanning…")
        io.execute {
            val full = try { Library.load(applicationContext) } catch (e: Exception) { Library(emptyList()) }
            val lib = filtered(full, prefs.excludedFolders)
            main.post {
                fullLibrary = full
                allFolders = full.songs.map { it.folder }.distinct().sorted()
                library = lib
                art.clear()
                maybeRestoreQueue()
                screen.refreshAll()
                if (announce) screen.flash("${lib.songs.size} songs")
            }
        }
    }

    // ------------------------------------------------------------------ music folders

    private fun filtered(full: Library, excluded: Set<String>) =
        if (excluded.isEmpty()) full else Library(full.songs.filter { it.folder !in excluded }, full.rawPlaylists)

    private fun subtree(path: String, exact: Boolean = false) = when {
        exact -> listOf(path)
        path.isEmpty() -> allFolders
        else -> allFolders.filter { it == path || it.startsWith("$path/") }
    }

    /** Direct sub-folders of [path] ("" = top level), as full paths. */
    fun folderChildren(path: String): List<String> = allFolders.mapNotNull { f ->
        when {
            path.isEmpty() -> f.substringBefore('/')
            f.startsWith("$path/") -> path + "/" + f.removePrefix("$path/").substringBefore('/')
            else -> null
        }
    }.distinct().sortedBy { sortKey(it.substringAfterLast('/')) }

    /** 0 = on, 1 = some sub-folders off, 2 = off */
    fun folderState(path: String, exact: Boolean = false): Int {
        val sub = subtree(path, exact)
        val ex = prefs.excludedFolders
        val off = sub.count { it in ex }
        return when {
            off == 0 -> 0
            off == sub.size -> 2
            else -> 1
        }
    }

    /** On -> Off; Off or partly on -> On. Applies to the folder and everything inside it. */
    fun toggleFolder(path: String, exact: Boolean = false) {
        val sub = subtree(path, exact)
        val ex = prefs.excludedFolders.toMutableSet()
        if (folderState(path, exact) == 0) ex.addAll(sub) else ex.removeAll(sub.toSet())
        prefs.excludedFolders = ex
        val full = fullLibrary ?: return
        io.execute {
            val lib = filtered(full, ex)
            main.post {
                library = lib
                screen.refreshAll()
            }
        }
    }

    // ------------------------------------------------------------------ keyboard prompt

    /** Shows a text box with the keyboard open. [onDone] gets the trimmed, non-empty text. */
    fun promptText(title: String, initial: String, onDone: (String) -> Unit) {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            setText(initial)
            setSelectAllOnFocus(true)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(60))
        }
        val box = FrameLayout(this).apply {
            val pad = (22 * density).toInt()
            setPadding(pad, (8 * density).toInt(), pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        fun submit() {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                input.error = "Enter a name"
                return
            }
            dialog.dismiss()
            onDone(text)
            screen.invalidate()
        }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                submit(); true
            } else false
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            input.requestFocus()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    // ------------------------------------------------------------------ GPod playlists

    fun nextPlaylistName(base: String = "New Playlist"): String {
        val taken = userPlaylists.all().map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { "$base $it" }.first { it !in taken }
    }

    fun createPlaylist(name: String, songs: List<String>) {
        val p = userPlaylists.create(name, songs)
        screen.selectKey = "playlist:${p.id}"
        screen.refreshAll()
        screen.selectKey = null
        screen.flash("Saved \u201C$name\u201D")
    }

    fun renamePlaylist(id: Long, name: String) {
        userPlaylists.update(id) { it.copy(name = name) }
        screen.refreshAll()
        screen.flash("Renamed")
    }

    /** Called from the delete confirmation page: leaves both it and the playlist. */
    fun deletePlaylist(id: Long) {
        userPlaylists.delete(id)
        screen.pop()
        screen.pop()
        screen.refreshAll()
        screen.flash("Playlist Deleted")
    }

    fun addToPlaylist(id: Long, s: Song) {
        val pl = userPlaylists.get(id) ?: return
        if (s.key in pl.songs) {
            screen.flash("Already in playlist"); return
        }
        userPlaylists.update(id) { it.copy(songs = it.songs + s.key) }
        screen.flash("Added to ${pl.name}")
    }

    fun removeFromPlaylist(id: Long, s: Song) {
        userPlaylists.update(id) { it.copy(songs = it.songs - s.key) }
        screen.flash("Removed")
    }

    // ------------------------------------------------------------------ playback helpers

    fun currentSong(): Song? {
        val id = controller?.currentMediaItem?.mediaId ?: return null
        return library?.byKey?.get(id)
    }

    fun hasQueue() = (controller?.mediaItemCount ?: 0) > 0
    fun shuffleOn() = controller?.shuffleModeEnabled ?: prefs.shuffle
    fun repeatMode() = controller?.repeatMode ?: prefs.repeatMode

    fun playSongs(songs: List<Song>, index: Int) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        c.setMediaItems(songs.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
        showNowPlaying()
    }

    fun shuffleAll() {
        val c = controller ?: return
        val songs = library?.songs.orEmpty()
        if (songs.isEmpty()) {
            screen.flash("No music"); return
        }
        c.shuffleModeEnabled = true
        prefs.shuffle = true
        c.setMediaItems(songs.map { it.toMediaItem() }, Random.nextInt(songs.size), 0L)
        c.prepare()
        c.play()
        showNowPlaying()
    }

    fun showNowPlaying() {
        if (screen.top !is NowPlayingPage) screen.push(NowPlayingPage(this))
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    /** Off -> All -> One -> Off */
    fun cycleRepeat() {
        val next = when (repeatMode()) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller?.repeatMode = next
        prefs.repeatMode = next
        screen.invalidate()
    }

    fun toggleShuffle() {
        val next = !shuffleOn()
        controller?.shuffleModeEnabled = next
        prefs.shuffle = next
        screen.invalidate()
    }

    // ------------------------------------------------------------------ Sleep / Exit

    /** Go to the home screen; music keeps playing and the app stays open as it was. */
    fun sleep() {
        saveQueue()
        moveTaskToBack(true)
    }

    /** Stops playback, clears the notification and closes the app. The queue is kept for next time. */
    fun exitApp() {
        saveQueue()
        controller?.pause()
        startService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_SLEEP))
        finishAndRemoveTask()
    }

    /** Returns false at max/min volume, so the wheel doesn't click when nothing changes. */
    fun adjustVolume(d: Int): Boolean {
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (d > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0,
        )
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) != before
    }

    fun volumeFraction(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    // ------------------------------------------------------------------ queue persistence

    private fun saveQueue() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        prefs.queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        prefs.queueCurrent = c.currentMediaItem?.mediaId ?: ""
        prefs.queuePosition = c.currentPosition
    }

    private fun maybeRestoreQueue() {
        if (restored) return
        val c = controller ?: return
        val lib = library ?: return
        restored = true
        if (c.mediaItemCount > 0) return
        val songs = prefs.queue.mapNotNull { lib.byKey[it] }
        if (songs.isEmpty()) return
        val idx = songs.indexOfFirst { it.key == prefs.queueCurrent }
        c.setMediaItems(songs.map { it.toMediaItem() }, idx.coerceAtLeast(0), if (idx >= 0) prefs.queuePosition else 0L)
        c.prepare()
    }

    // ------------------------------------------------------------------ player events

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncPlayerState()
            // Persist as we go so the queue survives the app being killed.
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) saveQueue()
            else if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) && player.mediaItemCount > 0) {
                prefs.queueCurrent = player.currentMediaItem?.mediaId ?: ""
                prefs.queuePosition = 0L
            }
        }
    }

    /**
     * The main menu's album-art half appears once music has played in this session,
     * so a fresh open shows a full-width menu instead of an empty art panel.
     */
    var showArtPanel = false
        private set

    private fun syncPlayerState() {
        val c = controller
        if (c?.isPlaying == true) showArtPanel = true
        screen.isPlaying = c?.isPlaying == true
        screen.hasMedia = (c?.mediaItemCount ?: 0) > 0
        screen.invalidate()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (screen.pages.isNotEmpty() && screen.top is NowPlayingPage) screen.invalidate()
            main.postDelayed(this, 250)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, i: Intent) {
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (lvl >= 0 && scale > 0) screen.batteryLevel = lvl.toFloat() / scale
            screen.charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 // on a charger, even when full
            screen.invalidate()
        }
    }

    // ------------------------------------------------------------------ click wheel

    override fun onWheel(delta: Int) {
        // Only click/vibrate when the turn did something (not at the end of a list, max volume, ...).
        if (screen.pages.isNotEmpty() && screen.top.onWheel(delta)) feedback.tick()
        screen.invalidate()
    }

    override fun onButton(b: ClickWheelView.Button) {
        feedback.press()
        when (b) {
            ClickWheelView.Button.MENU -> if (!screen.top.onMenu()) screen.pop()
            ClickWheelView.Button.CENTER -> screen.top.onSelect()
            ClickWheelView.Button.PLAY -> togglePlay()
            // When nothing is playing, pages like Cover Flow can use ⏮/⏭ to jump by letter.
            ClickWheelView.Button.NEXT -> if (controller?.isPlaying == true || !screen.top.onSkip(1))
                controller?.takeIf { it.mediaItemCount > 0 }?.seekToNext()
            ClickWheelView.Button.PREV -> if (controller?.isPlaying == true || !screen.top.onSkip(-1))
                controller?.takeIf { it.mediaItemCount > 0 }?.seekToPrevious()
        }
        screen.invalidate()
    }

    override fun onHoldStart(b: ClickWheelView.Button) {
        feedback.press()
        when (b) {
            ClickWheelView.Button.MENU -> screen.popToRoot()
            ClickWheelView.Button.NEXT -> startSeek(1)
            ClickWheelView.Button.PREV -> startSeek(-1)
            ClickWheelView.Button.PLAY -> togglePlay()
            ClickWheelView.Button.CENTER -> if (!screen.top.onHoldSelect()) screen.top.onSelect()
        }
        screen.invalidate()
    }

    override fun onHoldEnd(b: ClickWheelView.Button) {
        seekDir = 0
        main.removeCallbacks(seekRunnable)
    }

    private var seekDir = 0
    private var seekCount = 0

    private fun startSeek(dir: Int) {
        if (!hasQueue()) return
        seekDir = dir
        seekCount = 0
        main.removeCallbacks(seekRunnable)
        main.post(seekRunnable)
    }

    private val seekRunnable = object : Runnable {
        override fun run() {
            val c = controller ?: return
            if (seekDir == 0) return
            seekCount++
            val step = when {
                seekCount > 20 -> 15_000L
                seekCount > 8 -> 6_000L
                else -> 2_500L
            }
            var p = c.currentPosition + seekDir * step
            p = p.coerceAtLeast(0L)
            if (c.duration > 0) p = p.coerceAtMost(c.duration - 1000)
            c.seekTo(p)
            screen.invalidate()
            main.postDelayed(this, 200)
        }
    }
}
