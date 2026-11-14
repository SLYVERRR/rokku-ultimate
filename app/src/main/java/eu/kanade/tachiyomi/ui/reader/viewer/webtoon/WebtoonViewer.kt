package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.BaseViewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import uy.kohesive.injekt.injectLazy
import yokai.domain.ui.settings.ReaderPreferences
import yokai.i18n.MR
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Implementation of a [BaseViewer] to display pages with a [RecyclerView].
 */
class WebtoonViewer(val activity: ReaderActivity, val hasMargins: Boolean = false) : BaseViewer {

    val downloadManager: DownloadManager by injectLazy()
    private val readerPreferences: ReaderPreferences by injectLazy()

    private val scope = MainScope()

    private var autoScrollEnabled = false
    private var autoScrollPaused = false
    private var autoScrollHasRuntimeSpeedOverride = false
    private var autoScrollIntervalMillis = DEFAULT_AUTO_SCROLL_INTERVAL_MILLIS
    private var interruptAutoScrollDelay = false
    private var autoScrollToast: Toast? = null
    private var autoScrollUiBlocked = false
    private var autoScrollGesturesEnabled = true
    private var autoScrollGestureToastsEnabled = true

    /**
     * Recycler view used by this viewer.
     */
    val recycler = WebtoonRecyclerView(activity)

    /**
     * Frame containing the recycler view.
     */
    private val frame = WebtoonFrame(activity)

    /**
     * Distance to scroll when the user taps on one side of the recycler view.
     */
    private var scrollDistance = activity.resources.displayMetrics.heightPixels * 3 / 4

    /**
     * Distance (each side) beyond the visible viewport that the layout manager keeps laid out
     * and decoding. Larger than [scrollDistance] on purpose: pages that finish decoding while
     * still off-screen don't cause a visible reflow/jump once they scroll into view.
     */
    private val extraLayoutSpace = activity.resources.displayMetrics.heightPixels * 5 / 4

    /**
     * Layout manager of the recycler view.
     */
    private val layoutManager = WebtoonLayoutManager(activity, extraLayoutSpace)

    /**
     * Adapter of the recycler view.
     */
    private val adapter = WebtoonAdapter(this)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentPage: Any? = null

    /**
     * Configuration used by this viewer, like allow taps, or crop image borders.
     */
    val config = WebtoonConfig(scope)

    init {
        recycler.sidePaddingPercent = config.sidePadding
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) recycler.setItemViewCacheSize(RECYCLER_VIEW_CACHE_SIZE)
        recycler.isVisible = false // Don't let the recycler layout yet
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.autoScrollTogglePauseListener = ::toggleAutoScrollPause
        recycler.autoScrollActivateListener = ::startAutoScrollFromGesture
        recycler.autoScrollStopListener = ::stopAutoScroll
        recycler.autoScrollSpeedChangeListener = ::adjustAutoScrollSpeed
        recycler.autoScrollInteractionListener = {
            interruptAutoScrollDelay = true
        }
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onScrolled()

                    if (dy > config.menuThreshold || dy < -config.menuThreshold) {
                        activity.hideMenu()
                    }

                    if (dy < 0) {
                        val firstIndex = layoutManager.findFirstVisibleItemPosition()
                        val firstItem = adapter.items.getOrNull(firstIndex)
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            activity.requestPreloadChapter(firstItem.to)
                        }
                    }

                    val lastIndex = layoutManager.findLastEndVisibleItemPosition()
                    val lastItem = adapter.items.getOrNull(lastIndex)
                    if (lastItem is ChapterTransition.Next && lastItem.to == null) {
                        activity.showMenu()
                    }
                }
            },
        )
        recycler.tapListener = f@{ event ->
            val viewPosition = IntArray(2)
            recycler.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            recycler.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / recycler.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / recycler.originalHeight,
            )
            when (config.navigator.getAction(pos)) {
                ViewerNavigation.NavigationRegion.MENU -> activity.toggleMenu()
                ViewerNavigation.NavigationRegion.NEXT, ViewerNavigation.NavigationRegion.RIGHT -> moveToNext()
                ViewerNavigation.NavigationRegion.PREV, ViewerNavigation.NavigationRegion.LEFT -> moveToPrevious()
            }
        }
        recycler.longTapListener = f@{ event ->
            if (activity.menuVisible || config.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        activity.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }

        config.imagePropertyChangedListener = {
            recycler.sidePaddingPercent = config.sidePadding
            refreshAdapter()
        }

        config.zoomPropertyChangedListener = {
            frame.enableZoomOut = it
        }

        config.doubleTapZoomChangedListener = {
            frame.doubleTapZoom = it
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayForNewUser
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }
        config.navigationModeInvertedListener = { activity.binding.navigationOverlay.showNavigationAgain() }

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
        val nextChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter

        // Allow preload for
        // 1. Going between pages of same chapter
        // 2. Next chapter page
        return when (page.chapter) {
            (currentPage as? ReaderPage)?.chapter -> true
            nextChapter -> true
            else -> false
        }
    }

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return frame
    }

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    override fun destroy() {
        super.destroy()
        autoScrollToast?.cancel()
        scope.cancel()
    }

    /**
     * Called from the RecyclerView listener when a [page] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onPageSelected(page: ReaderPage, allowPreload: Boolean) {
        val pages = page.chapter.pages ?: return
        Logger.d { "onPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page, false)

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            Logger.d { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
            val transitionChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as?ReaderPage)?.chapter
            if (transitionChapter != null) {
                Logger.d { "Requesting to preload chapter ${transitionChapter.chapter.chapter_number}" }
                activity.requestPreloadChapter(transitionChapter)
            }
        }
    }

    /**
     * Called from the RecyclerView listener when a [transition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        Logger.d { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            Logger.d { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    override fun setChapters(chapters: ViewerChapters) {
        Logger.d { "setChapters" }
        val forceTransition = config.alwaysShowChapterTransition || currentPage is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        if (recycler.isGone) {
            Logger.d { "Recycler first layout" }
            val pages = chapters.currChapter.pages ?: return
            if (pages.isEmpty()) return
            moveToPage(pages[chapters.currChapter.requestedPage.coerceIn(0, pages.lastIndex)])
            recycler.isVisible = true
        }
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage, animated: Boolean) {
        Logger.d { "moveToPage" }
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            recycler.scrollToPosition(position)
            if (layoutManager.findLastEndVisibleItemPosition() == -1) {
                onScrolled(position)
            }
        } else {
            Logger.d { "Page $page not found in adapter" }
        }
    }

    fun onScrolled(pos: Int? = null) {
        val position = pos ?: layoutManager.findLastEndVisibleItemPosition()
        val item = adapter.items.getOrNull(position)
        val allowPreload = checkAllowPreload(item as? ReaderPage)
        if (item != null && currentPage != item) {
            currentPage = item
            when (item) {
                is ReaderPage -> onPageSelected(item, allowPreload)
                is ChapterTransition -> onTransitionSelected(item)
            }
        }
    }

    /**
     * Scrolls up by [scrollDistance].
     */
    override fun moveToPrevious() {
        recycler.smoothScrollBy(0, -scrollDistance)
    }

    /**
     * Scrolls down by [scrollDistance].
     */
    override fun moveToNext() {
        recycler.smoothScrollBy(0, scrollDistance)
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveToNext() else moveToPrevious()
                }
            }

            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveToPrevious() else moveToNext()
                }
            }

            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> if (isUp) moveToPrevious()

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> if (isUp) moveToNext()

            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    /**
     * Notifies adapter of changes around the current page to trigger a relayout in the recycler.
     * Used when an image configuration is changed.
     */
    private fun refreshAdapter() {
        val position = layoutManager.findLastEndVisibleItemPosition()
        adapter.notifyItemRangeChanged(
            max(0, position - 3),
            min(position + 3, adapter.itemCount - 1),
        )
    }

    fun syncAutoScrollSettings(enabled: Boolean, interval: Duration, forceInterval: Boolean = false) {
        val intervalMillis =
            interval.inWholeMilliseconds
                .coerceIn(MIN_AUTO_SCROLL_INTERVAL_MILLIS, MAX_AUTO_SCROLL_INTERVAL_MILLIS)

        if (!enabled) {
            autoScrollEnabled = false
            autoScrollPaused = false
            autoScrollHasRuntimeSpeedOverride = false
            interruptAutoScrollDelay = false
            recycler.stopScroll()
            updateAutoScrollGestureMode()
            return
        }

        if (!autoScrollEnabled || forceInterval) {
            autoScrollIntervalMillis = intervalMillis
            autoScrollHasRuntimeSpeedOverride = false
            autoScrollPaused = false
        } else if (!autoScrollHasRuntimeSpeedOverride) {
            autoScrollIntervalMillis = intervalMillis
        }

        autoScrollEnabled = true
        updateAutoScrollGestureMode()
    }

    fun setAutoScrollUiBlocked(blocked: Boolean) {
        autoScrollUiBlocked = blocked
        if (blocked) {
            recycler.stopScroll()
            interruptAutoScrollDelay = true
        }
        updateAutoScrollGestureMode()
    }

    fun setAutoScrollGesturesEnabled(enabled: Boolean) {
        autoScrollGesturesEnabled = enabled
        updateAutoScrollGestureMode()
    }

    fun setAutoScrollGestureToastsEnabled(enabled: Boolean) {
        autoScrollGestureToastsEnabled = enabled
        if (!enabled) {
            autoScrollToast?.cancel()
            autoScrollToast = null
        }
    }

    fun performAutoScrollStep(smooth: Boolean) {
        if (
            !autoScrollEnabled ||
            autoScrollPaused ||
            recycler.isAutoScrollGestureInProgress() ||
            autoScrollUiBlocked
        ) {
            return
        }

        if (smooth) {
            linearScroll(autoScrollIntervalMillis.milliseconds)
        } else {
            moveToNext()
        }
    }

    fun autoScrollDelayMillis(): Long {
        return if (autoScrollPaused || autoScrollUiBlocked || recycler.isAutoScrollGestureInProgress()) {
            AUTO_SCROLL_POLL_DELAY_MILLIS
        } else {
            autoScrollIntervalMillis
        }
    }

    fun consumeAutoScrollDelayInterrupt(): Boolean {
        val shouldInterrupt = interruptAutoScrollDelay
        interruptAutoScrollDelay = false
        return shouldInterrupt
    }

    fun linearScroll(duration: Duration) {
        recycler.smoothScrollBy(
            0,
            activity.resources.displayMetrics.heightPixels,
            LinearInterpolator(),
            duration.inWholeMilliseconds.toInt(),
        )
    }

    private fun toggleAutoScrollPause() {
        if (!autoScrollEnabled) return

        autoScrollPaused = !autoScrollPaused
        interruptAutoScrollDelay = true
        recycler.stopScroll()

        val action = if (autoScrollPaused) {
            activity.getString(MR.strings.action_pause)
        } else {
            activity.getString(MR.strings.action_resume)
        }
        showAutoScrollToast("${activity.getString(MR.strings.autoscroll)}: $action")
    }

    private fun stopAutoScroll() {
        if (!autoScrollEnabled) return

        autoScrollEnabled = false
        autoScrollPaused = false
        autoScrollHasRuntimeSpeedOverride = false
        interruptAutoScrollDelay = true
        updateAutoScrollGestureMode()
        recycler.stopScroll()
        activity.toggleAutoScroll(false)
        showAutoScrollToast(
            "${activity.getString(MR.strings.autoscroll)}: ${activity.getString(MR.strings.action_stop)}",
        )
    }

    private fun adjustAutoScrollSpeed(steps: Int) {
        if (!autoScrollEnabled || steps == 0) return

        recycler.stopScroll()
        interruptAutoScrollDelay = true
        autoScrollHasRuntimeSpeedOverride = true

        var newInterval = autoScrollIntervalMillis.toDouble()
        repeat(abs(steps)) {
            newInterval *= if (steps > 0) {
                AUTO_SCROLL_SPEED_UP_FACTOR
            } else {
                AUTO_SCROLL_SPEED_DOWN_FACTOR
            }
        }

        autoScrollIntervalMillis =
            newInterval.roundToLong().coerceIn(
                MIN_AUTO_SCROLL_INTERVAL_MILLIS,
                MAX_AUTO_SCROLL_INTERVAL_MILLIS,
            )

        showAutoScrollToast(
            "${activity.getString(MR.strings.autoscroll)}: ${formatAutoScrollInterval()}",
        )
    }

    private fun formatAutoScrollInterval(): String {
        val seconds = autoScrollIntervalMillis / 1000f
        val pattern = if (seconds < 10f) "%.2fs" else "%.1fs"
        return String.format(Locale.getDefault(), pattern, seconds)
    }

    private fun showAutoScrollToast(message: String) {
        if (!autoScrollGestureToastsEnabled) return

        autoScrollToast?.cancel()
        autoScrollToast = Toast.makeText(activity, message, Toast.LENGTH_SHORT).apply { show() }
    }

    private fun startAutoScrollFromGesture() {
        if (autoScrollEnabled || readerPreferences.autoscrollInterval().get() <= 0f) return

        activity.toggleAutoScroll(true)
        showAutoScrollToast(
            "${activity.getString(MR.strings.autoscroll)}: ${activity.getString(MR.strings.action_start)}",
        )
    }

    private fun updateAutoScrollGestureMode() {
        val gesturesAvailable = autoScrollGesturesEnabled && !autoScrollUiBlocked
        recycler.autoScrollGestureModeEnabled = gesturesAvailable && autoScrollEnabled
        recycler.autoScrollActivationGestureEnabled =
            gesturesAvailable &&
            !autoScrollEnabled &&
            readerPreferences.autoscrollInterval().get() > 0f
    }
}

private const val RECYCLER_VIEW_CACHE_SIZE = 4

private const val AUTO_SCROLL_POLL_DELAY_MILLIS = 100L
private const val DEFAULT_AUTO_SCROLL_INTERVAL_MILLIS = 3_000L
private const val MIN_AUTO_SCROLL_INTERVAL_MILLIS = 250L
private const val MAX_AUTO_SCROLL_INTERVAL_MILLIS = 9_999_000L
private const val AUTO_SCROLL_SPEED_UP_FACTOR = 0.9
private const val AUTO_SCROLL_SPEED_DOWN_FACTOR = 1.1
