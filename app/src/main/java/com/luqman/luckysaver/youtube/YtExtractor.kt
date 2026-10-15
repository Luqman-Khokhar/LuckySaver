package com.luqman.luckysaver.youtube

import com.luqman.luckysaver.core.FailureKind
import com.luqman.luckysaver.core.ResolveException
import com.luqman.luckysaver.core.YtLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException

/** One downloadable file behind a format: a signed URL that expires within hours. */
data class YtPart(val url: String, val size: Long, val itag: Int)

/**
 * What to fetch for a choice. Video above 360p comes as separate video and audio files that have
 * to be muxed; [muxed] is the rare single file that already has both.
 */
data class YtPick(val video: YtPart?, val audio: YtPart?, val muxed: YtPart?, val height: Int) {
    val audioOnly: Boolean get() = video == null && muxed == null
    val totalSize: Long get() = listOfNotNull(video, audio, muxed).sumOf { it.size.coerceAtLeast(0) }
}

/** A choice offered in the sheet. [height] 0 means audio only. */
data class YtFormat(val height: Int, val size: Long) {
    val audioOnly: Boolean get() = height == 0
    val label: String get() = if (audioOnly) "Audio only" else "${height}p"
}

data class YtVideo(
    val link: YtLink,
    val title: String,
    val channel: String,
    val durationSec: Long,
    val thumbnailUrl: String,
    /** Epoch seconds; 0 when YouTube didn't say. */
    val uploadedAt: Long,
    /** Video heights best first, then audio only. */
    val formats: List<YtFormat>,
)

/**
 * YouTube through NewPipeExtractor. Stream URLs are tied to the player that produced them and run
 * out within hours, so the download worker resolves again right before fetching rather than
 * trusting URLs from the sheet.
 */
class YtExtractor(http: OkHttpClient) {

    init {
        NewPipe.init(OkDownloader(http))
    }

    suspend fun info(link: YtLink): StreamInfo = withContext(Dispatchers.IO) {
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, link.watchUrl)
        } catch (e: Exception) {
            throw describe(e)
        }
        if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) {
            throw ResolveException(
                "That's a live stream. It can be saved once the broadcast has ended.", FailureKind.UNSUPPORTED_LINK,
            )
        }
        info
    }

    suspend fun lookup(link: YtLink): YtVideo {
        val info = info(link)
        val heights = (videoOnly(info).map { heightOf(it) } + progressive(info).map { heightOf(it) })
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
        val formats = heights.map { h -> YtFormat(h, runCatching { pick(info, h, audioOnly = false).totalSize }.getOrDefault(0)) } +
            listOfNotNull(bestAudio(info)?.let { YtFormat(0, sizeOf(it)) })
        if (formats.isEmpty()) throw nothingSaveable()
        return YtVideo(
            link = link,
            title = info.name.orEmpty().ifBlank { link.videoId },
            channel = info.uploaderName.orEmpty().removeSuffix(" - Topic"),
            durationSec = info.duration,
            thumbnailUrl = thumbnailUrl(link.videoId),
            uploadedAt = runCatching { info.uploadDate?.instant?.epochSecond }.getOrNull() ?: 0,
            formats = formats,
        )
    }

    /**
     * The closest match to [maxHeight] without going over (or the smallest there is, if every
     * format is bigger). H.264 + AAC only: those are what MediaMuxer can put in an MP4.
     */
    fun pick(info: StreamInfo, maxHeight: Int, audioOnly: Boolean): YtPick {
        val audio = bestAudio(info)
        if (audioOnly) {
            audio ?: throw nothingSaveable()
            return YtPick(video = null, audio = part(audio, audio.itag), muxed = null, height = 0)
        }
        val video = closest(videoOnly(info), maxHeight)
        val single = closest(progressive(info), maxHeight)
        return when {
            // A pre-muxed file is only worth taking when it's as good as the split pair.
            single != null && (video == null || audio == null || heightOf(single) >= heightOf(video)) ->
                YtPick(video = null, audio = null, muxed = part(single, single.itag), height = heightOf(single))
            video != null && audio != null ->
                YtPick(video = part(video, video.itag), audio = part(audio, audio.itag), muxed = null, height = heightOf(video))
            else -> throw nothingSaveable()
        }
    }

    private fun videoOnly(info: StreamInfo): List<VideoStream> = info.videoOnlyStreams.filter {
        it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
            it.format == MediaFormat.MPEG_4 && it.codec.orEmpty().startsWith("avc1")
    }

    private fun progressive(info: StreamInfo): List<VideoStream> = info.videoStreams.filter {
        it.isUrl && !it.isVideoOnly && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.MPEG_4
    }

    /** AAC in M4A, the original language track when the video has dubs. */
    private fun bestAudio(info: StreamInfo): AudioStream? = info.audioStreams
        .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.M4A }
        .sortedWith(
            compareByDescending<AudioStream> { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
                .thenByDescending { it.averageBitrate },
        )
        .firstOrNull()

    private fun closest(streams: List<VideoStream>, maxHeight: Int): VideoStream? {
        val ordered = streams.sortedWith(compareByDescending<VideoStream> { heightOf(it) }.thenByDescending { it.bitrate })
        return ordered.firstOrNull { heightOf(it) <= maxHeight } ?: ordered.lastOrNull()
    }

    private fun part(stream: org.schabi.newpipe.extractor.stream.Stream, itag: Int) = YtPart(stream.content, sizeOf(stream), itag)

    private fun sizeOf(stream: org.schabi.newpipe.extractor.stream.Stream): Long =
        stream.itagItem?.contentLength?.takeIf { it > 0 }
            ?: Regex("""[?&]clen=(\d+)""").find(stream.content)?.groupValues?.get(1)?.toLongOrNull()
            ?: -1

    private fun nothingSaveable() = ResolveException(
        "YouTube offered no format LuckySaver can save for that video", FailureKind.UNREADABLE,
    )

    private fun describe(e: Exception): ResolveException = when (e) {
        is ResolveException -> e
        is AgeRestrictedContentException -> ResolveException(
            "That video is age-restricted. YouTube only plays it to signed-in adults.", FailureKind.RESTRICTED, cause = e,
        )
        is YoutubeMusicPremiumContentException, is PaidContentException -> ResolveException(
            "Only paying members can play that on YouTube.", FailureKind.RESTRICTED, cause = e,
        )
        is PrivateContentException -> ResolveException("That video is private.", FailureKind.RESTRICTED, cause = e)
        is GeographicRestrictionException -> ResolveException(
            "That video isn't available in your country.", FailureKind.RESTRICTED, cause = e,
        )
        is SignInConfirmNotBotException, is ReCaptchaException -> ResolveException(
            "YouTube wants to check this connection isn't a bot. Try again later, or switch between Wi-Fi and mobile data.",
            FailureKind.BOT_CHECK, cause = e,
        )
        is ContentNotSupportedException -> ResolveException(
            "LuckySaver can't save that kind of YouTube video.", FailureKind.UNSUPPORTED_LINK, cause = e,
        )
        is ContentNotAvailableException -> ResolveException(
            "That video isn't available. It may have been removed.", FailureKind.NOT_FOUND, cause = e,
        )
        is IOException -> ResolveException(
            "No connection to YouTube. Check your network.", FailureKind.NETWORK, cause = e,
        )
        is ExtractionException -> ResolveException(
            "YouTube changed something LuckySaver relies on. An app update is needed.", FailureKind.UNREADABLE, cause = e,
        )
        else -> ResolveException(e.message ?: "Couldn't read that video", FailureKind.UNKNOWN, cause = e)
    }

    companion object {
        private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        /** "1080p60" and "720p" both carry the height first; Shorts report it the same way. */
        fun heightOf(stream: VideoStream): Int =
            stream.getResolution().takeWhile { it.isDigit() }.toIntOrNull() ?: minOf(stream.width, stream.height)

        /** YouTube's own thumbnail path, stable for the life of the video: no need to store the URL. */
        fun thumbnailUrl(videoId: String) = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

        /**
         * Stream URLs only work for the client they were issued to, so the request has to look like
         * that client: the Android and iOS apps by user agent, the web player by origin.
         */
        fun streamHeaders(url: String): Map<String, String> = when {
            YoutubeParsingHelper.isAndroidStreamingUrl(url) ->
                mapOf("User-Agent" to YoutubeParsingHelper.getAndroidUserAgent(Localization.DEFAULT))
            YoutubeParsingHelper.isIosStreamingUrl(url) ->
                mapOf("User-Agent" to YoutubeParsingHelper.getIosUserAgent(Localization.DEFAULT))
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) ->
                mapOf("User-Agent" to YoutubeParsingHelper.getVisionOsUserAgent(Localization.DEFAULT))
            else -> mapOf(
                "User-Agent" to BROWSER_UA,
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/",
            )
        }
    }

    /** NewPipe's network layer on the app's OkHttp client. */
    private class OkDownloader(private val http: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val method = request.httpMethod()
            val data = request.dataToSend()
            val body = when {
                data != null -> data.toRequestBody()
                method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody()
                else -> null
            }
            val builder = okhttp3.Request.Builder()
                .url(request.url())
                .method(method, body)
                .header("User-Agent", BROWSER_UA)
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            http.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                return Response(
                    resp.code, resp.message, resp.headers.toMultimap(), resp.body?.string(), resp.request.url.toString(),
                )
            }
        }
    }
}
