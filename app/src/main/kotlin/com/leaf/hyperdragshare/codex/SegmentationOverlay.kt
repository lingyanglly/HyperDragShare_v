package com.leaf.hyperdragshare.codex

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * Text segmentation rendered as a real overlay window instead of a full-screen Activity.
 *
 * <p>The window belongs to the process that owns the drag session: the injected portal
 * (TYPE_SYSTEM_DIALOG) or the accessibility service (TYPE_ACCESSIBILITY_OVERLAY). The
 * chips still come from the imported BoomChipPage, which is now Activity-free; the
 * tokenizer stays in the module process and is reached through a provider RPC because
 * the portal process has no cppjieba native library.</p>
 */
internal class SegmentationOverlay(
    private val hostContext: Context,
    private val policy: OverlayWindowPolicy,
    private val dark: Boolean,
    private val text: String,
    private val touchX: Int,
    private val touchY: Int,
    private val onClosed: Runnable,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager =
        hostContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var resourceContext: Context = hostContext
    private var overlayView: SegmentationOverlayView? = null
    private var boomChipPage: BoomChipPage? = null
    private var dismissRequester: (() -> Unit)? = null
    private var closed = false

    fun show() {
        resourceContext = moduleContext(hostContext)
        // The portal process does not put the module APK on the package context's
        // classloader path, so inflating the BigBang layout would fail on CustomScrollView.
        // Compose also reads applicationContext, which is null for a package context that
        // has no live Application in this process; keep the host application instead.
        val contentView = LayoutInflater.from(resourceContext)
            .cloneInContext(resourceContext)
            .inflate(R.layout.boom_activity_layout, null, false)
        contentView.findViewById<View>(R.id.boom_page).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            BoomAnimator.makeFadeIn(this, BoomAnimator.BOOM_DURATION)
        }
        boomChipPage = BoomChipPage(
            resourceContext,
            object : BoomChipPage.Host {
                override fun requestDismiss() {
                    dismissRequester?.invoke() ?: close()
                }

                override fun openSearch(searchText: String, type: Int) {
                    openSearchImpl(searchText, type)
                }
            },
            contentView,
            false,
        )
        val view = SegmentationOverlayView(
            composeContext = hostContext,
            resourceContext = resourceContext,
            contentView = contentView,
            touchX = touchX,
            touchY = touchY,
            dark = dark,
            onDismissRequesterChanged = { dismissRequester = it },
            onDismissRequest = { boomChipPage?.handleClick() != true },
            onDismissFinished = { close() },
            onEditMode = { showPlaceholder() },
            onSelectAll = { boomChipPage?.selectAll() },
            onShareAll = { shareAll() },
            onMore = { showPlaceholder() },
            onBack = { dismissRequester?.invoke() ?: close() },
        )
        overlayView = view
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            policy.windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "DragShare segmentation"
            val refreshRate = DisplayRefreshRate.max(hostContext)
            if (refreshRate > 0f) {
                preferredRefreshRate = refreshRate
            }
            val modeId = DisplayRefreshRate.maxModeId(hostContext)
            if (modeId > 0) {
                preferredDisplayModeId = modeId
            }
        }
        try {
            windowManager.addView(view, params)
            view.requestFocus()
            DragShareLog.i(TAG, "segmentation overlay added type=" + policy.windowType)
        } catch (error: Throwable) {
            DragShareLog.w(TAG, "unable to add segmentation overlay", error)
            close()
            return
        }
        Thread({
            val segment = ImageStagingClient.segmentText(hostContext, text)
            mainHandler.post {
                if (closed) {
                    return@post
                }
                if (segment == null || segment.isEmpty()) {
                    toast(resourceContext.getString(R.string.a_msg_no_words))
                    close()
                    return@post
                }
                val page = boomChipPage
                if (page == null || !page.initWords(segment, text, -1, touchX, touchY)) {
                    toast(resourceContext.getString(R.string.a_msg_no_words))
                    close()
                }
            }
        }, "drag-share-segmentation-overlay").start()
    }

    fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { close() }
            return
        }
        if (closed) {
            return
        }
        closed = true
        dismissRequester = null
        val view = overlayView
        overlayView = null
        if (view != null) {
            try {
                windowManager.removeView(view)
            } catch (ignored: Throwable) {
                // The window may already be gone with the host process.
            }
            try {
                view.disposeOverlay()
            } catch (ignored: Throwable) {
                // Compose teardown is best-effort.
            }
        }
        boomChipPage = null
        try {
            onClosed.run()
        } catch (ignored: Throwable) {
            // Callback is optional diagnostics / service-stop flushing.
        }
    }

    private fun shareAll() {
        val shareText = boomChipPage?.originalText ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        try {
            hostContext.startActivity(
                Intent.createChooser(send, null)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (error: Throwable) {
            DragShareLog.w(TAG, "segmentation share failed", error)
        }
    }

    private fun openSearchImpl(searchText: String, type: Int) {
        if (searchText.isBlank()) {
            return
        }
        val query = if (type == BoomActionHandler.SEARCH_DICTIONARY) "$searchText 词典" else searchText
        try {
            hostContext.startActivity(
                Intent(Intent.ACTION_WEB_SEARCH)
                    .putExtra(SearchManager.QUERY, query)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (ignored: Throwable) {
            try {
                val uri = Uri.Builder()
                    .scheme("https")
                    .authority("www.google.com")
                    .appendPath("search")
                    .appendQueryParameter("q", query)
                    .build()
                hostContext.startActivity(
                    Intent(Intent.ACTION_VIEW, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (ignoredAgain: Throwable) {
                toast("无法打开搜索")
            }
        }
    }

    private fun showPlaceholder() {
        toast(resourceContext.getString(R.string.bigbang_action_placeholder))
    }

    private fun toast(message: String) {
        try {
            Toast.makeText(hostContext, message, Toast.LENGTH_SHORT).show()
        } catch (ignored: Throwable) {
            // Toast can be unavailable in a dying process.
        }
    }

    private fun moduleContext(context: Context): Context {
        val base = if (context.packageName == MODULE_PACKAGE) {
            context
        } else {
            try {
                context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            } catch (error: Throwable) {
                DragShareLog.w(TAG, "unable to create module context", error)
                context
            }
        }
        val application = context.applicationContext ?: context
        return object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): Context = application

            override fun getClassLoader(): ClassLoader {
                return BoomChipPage::class.java.classLoader ?: super.getClassLoader()
            }
        }
    }

    companion object {
        private const val TAG = "DragShare/Segmentation"
        private const val MODULE_PACKAGE = "com.leaf.hyperdragshare.codex"
    }
}

/**
 * Full-screen Compose host for the segmentation panel. It reuses the Activity shell but
 * is focusable so the back key can dismiss the window.
 */
private class SegmentationOverlayView(
    composeContext: Context,
    private val resourceContext: Context,
    contentView: View,
    touchX: Int,
    touchY: Int,
    dark: Boolean,
    onDismissRequesterChanged: ((() -> Unit)?) -> Unit,
    onDismissRequest: () -> Boolean,
    onDismissFinished: () -> Unit,
    onEditMode: () -> Unit,
    onSelectAll: () -> Unit,
    onShareAll: () -> Unit,
    onMore: () -> Unit,
    private val onBack: () -> Unit,
) : ModernOverlayComposeView(composeContext) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        clipToOutline = false
        outlineProvider = null
        setOverlayContent {
            // Compose runs on the host application context so its recomposer can register
            // component callbacks; the shell still resolves module strings through LocalContext.
            CompositionLocalProvider(LocalContext provides resourceContext) {
                BigBangOverlayContent(
                    contentView = contentView,
                    touchX = touchX,
                    touchY = touchY,
                    dark = dark,
                    onDismissRequesterChanged = onDismissRequesterChanged,
                    onDismissRequest = onDismissRequest,
                    onDismissFinished = onDismissFinished,
                    onEditMode = onEditMode,
                    onSelectAll = onSelectAll,
                    onShareAll = onShareAll,
                    onMore = onMore,
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                onBack()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
