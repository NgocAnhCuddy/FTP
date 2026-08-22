package com.myfile.ui.media

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * Bọc việc kết nối tới AudioPlaybackService bằng MediaController (theo đúng pattern chuẩn
 * của Media3): Activity gọi connect() 1 lần, nhận về MediaController để play/pause/seek,
 * và phải gọi release() khi Activity destroy để tránh rò rỉ kết nối.
 */
class AudioPlayerController(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    fun connect(onReady: (MediaController) -> Unit) {
        connect(onReady = onReady, onError = null)
    }

    /**
     * [onError] tùy chọn: gọi khi kết nối MediaController thất bại — ví dụ lần đầu mở app,
     * AudioPlaybackService (foreground service) khởi động nguội (cold start) chậm hoặc bị hệ
     * thống từ chối tạm thời trên máy yếu, khiến future.get() ném ExecutionException/
     * TimeoutException. Trước đây exception này KHÔNG được bắt -> crash ngay khi mở màn hình
     * xem media lần đầu; mở lại vài lần sau đó service đã "ấm" (warm) nên connect nhanh và
     * không crash nữa — đúng triệu chứng "lần đầu crash, mở 2-3 lần mới xem được".
     */
    fun connect(onReady: (MediaController) -> Unit, onError: ((Throwable) -> Unit)?) {
        val sessionToken = SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                controller = future.get()
                controller?.let(onReady)
            } catch (e: Exception) {
                // CancellationException khi release() gọi trước khi future hoàn tất (activity
                // bị đóng sớm) là bình thường, không phải lỗi -> không cần báo onError.
                if (e !is java.util.concurrent.CancellationException) {
                    android.util.Log.w("AudioPlayerController", "Kết nối MediaController thất bại", e)
                    onError?.invoke(e)
                }
            }
        }, MoreExecutors.directExecutor())
    }

    /** Phát 1 danh sách file audio, bắt đầu từ vị trí [startIndex]. */
    fun playPlaylist(items: List<AudioTrack>, startIndex: Int = 0) {
        val mediaItems = items.map { track ->
            MediaItem.Builder()
                .setUri(track.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .build()
                )
                .build()
        }
        controller?.apply {
            setMediaItems(mediaItems, startIndex, 0L)
            prepare()
            play()
        }
    }

    fun playPause() {
        controller?.let { c ->
            if (c.isPlaying) c.pause() else c.play()
        }
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPreviousMediaItem()
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    fun release() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
    }
}

/** 1 bài nhạc/file audio để đưa vào hàng đợi phát. */
data class AudioTrack(
    val uri: String,
    val title: String
)
