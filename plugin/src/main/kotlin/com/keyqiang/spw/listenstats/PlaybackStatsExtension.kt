package com.keyqiang.spw.listenstats

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

/**
 * 播放扩展点：把宿主回调转成引擎输入，并**逐类计数**用于远程排障。
 *
 * 三条铁律：
 * 1. 所有回调**绝不抛异常**（异常会冒到宿主里），统一包 try/catch 并写进日志文件
 * 2. 先计数、再取引擎——这样「宿主在调我但引擎是空的」和「宿主根本没调我」能区分开
 * 3. 每一类回调**首次触发时**写一行带上下文的日志，之后只计数（不刷屏）
 */
@Extension
class PlaybackStatsExtension : PlaybackExtensionPoint {

    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        ListenStatsPlugin.callback("onBeforeLoadLyrics", mediaItem.describe())
        safe("onBeforeLoadLyrics") { ListenStatsPlugin.engineOrNull()?.onMediaItem(mediaItem.toMediaInfo()) }
        return null
    }

    override fun onAfterLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        ListenStatsPlugin.callback("onAfterLoadLyrics", mediaItem.describe())
        safe("onAfterLoadLyrics") { ListenStatsPlugin.engineOrNull()?.onMediaItem(mediaItem.toMediaInfo()) }
        return null
    }

    override fun onPositionUpdated(position: Long) {
        ListenStatsPlugin.callback("onPositionUpdated", "position=" + position + "ms")
        safe("onPositionUpdated") { ListenStatsPlugin.engineOrNull()?.onPosition(position) }
    }

    override fun onSeekTo(position: Long) {
        ListenStatsPlugin.callback("onSeekTo", "position=" + position + "ms")
        safe("onSeekTo") { ListenStatsPlugin.engineOrNull()?.onSeekTo(position) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        ListenStatsPlugin.callback("onIsPlayingChanged", "isPlaying=" + isPlaying)
        safe("onIsPlayingChanged") { ListenStatsPlugin.engineOrNull()?.onPlayState(isPlaying) }
    }

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        ListenStatsPlugin.callback("onStateChanged", "state=" + state)
        val mapped = when (state) {
            PlaybackExtensionPoint.State.Idle -> PlayerState.Idle
            PlaybackExtensionPoint.State.Buffering -> PlayerState.Buffering
            PlaybackExtensionPoint.State.Ready -> PlayerState.Ready
            PlaybackExtensionPoint.State.Ended -> PlayerState.Ended
        }
        safe("onStateChanged") { ListenStatsPlugin.engineOrNull()?.onPlayerState(mapped) }
    }

    /**
     * 只计数、不参与统计。
     *
     * 它的价值在排障：如果它一直在触发、但 `onBeforeLoadLyrics` 从没出现，
     * 就能定位成「宿主走了歌词流程却没给曲目回调」，而不是插件的问题。
     */
    override fun onLyricsLineUpdated(lyricsLine: PlaybackExtensionPoint.LyricsLine?) {
        ListenStatsPlugin.callback(
            "onLyricsLineUpdated",
            if (lyricsLine == null) "无歌词行" else "mainText=" + lyricsLine.pureMainText.take(20)
        )
    }

    private fun PlaybackExtensionPoint.MediaItem.describe(): String = describeMedia(toMediaInfo())

    private fun PlaybackExtensionPoint.MediaItem.toMediaInfo() = MediaInfo(
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        path = path
    )

    private inline fun safe(context: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            ListenStatsPlugin.extensionError(context, t)
        }
    }
}
