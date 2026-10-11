package com.selkies.client.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.selkies.client.core.SelkiesSession
import com.selkies.client.video.AndroidVideoSink

/**
 * Android 视频表面：SurfaceView + MediaCodec 直渲
 */
@Composable
actual fun VideoSurface(session: SelkiesSession, modifier: Modifier) {
    val sink = remember(session) { (session.videoSink as? AndroidVideoSink) ?: AndroidVideoSink().also { session.videoSink = it } }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.setKeepScreenOn(true)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        sink.attachSurface(holder.surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        sink.detachSurface()
                    }
                })
            }
        },
        modifier = modifier
    )

    DisposableEffect(session) {
        onDispose {
            session.videoSink?.let { runCatching { it.close() } }
            session.videoSink = null
        }
    }
}

/**
 * Android IME 宿主：隐藏 EditText 承接输入法，逐键实时发送
 */
@Composable
actual fun ImeHost(
    visible: Boolean,
    onCommitText: (String) -> Unit,
    onEnter: () -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier
) {
    AndroidView(
        factory = { ctx ->
            android.widget.EditText(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                alpha = 0f
                isSingleLine = true
                textSize = 1f
                setPadding(0, 0, 0, 0)
                background = null
                addTextChangedListener(object : android.text.TextWatcher {
                    private var last: String = ""
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val cur = s?.toString() ?: ""
                        val old = last
                        last = cur
                        when {
                            cur.length > old.length && cur.startsWith(old) -> {
                                onCommitText(cur.substring(old.length))
                            }
                            cur.length < old.length && old.startsWith(cur) -> {
                                repeat(old.length - cur.length) { onBackspace() }
                            }
                            cur != old -> {
                                repeat(old.length) { onBackspace() }
                                if (cur.isNotEmpty()) onCommitText(cur)
                            }
                        }
                        if (last.length > 200) {
                            last = ""
                            s?.clear()
                        }
                    }
                })
                setOnEditorActionListener { _, actionId, event ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                        actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                        actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                        actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT ||
                        (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)
                    ) {
                        onEnter()
                        true
                    } else false
                }
            }
        },
        modifier = modifier,
        update = { edit ->
            if (visible) {
                if (!edit.hasFocus()) edit.requestFocus()
                val imm = edit.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    )
}
