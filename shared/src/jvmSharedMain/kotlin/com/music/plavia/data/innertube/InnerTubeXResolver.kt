package com.music.plavia.data.innertube

import com.music.plavia.data.MonotonicClock
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities

import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.models.YouTubeLocale
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.music.plavia.data.Http
import com.music.plavia.data.PlaybackNotices
import com.music.plavia.data.TrackLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** YouTube stream extraction through InnerTubeX's live-benchmarked client catalog and cipher tiers. */
object InnerTubeXResolver {

    private const val TAG = "BitChord"

    /** A stream InnerTubeX found, and the identity its media fetch has to wear. */
    class Extracted(
        val videoId: String,
        val url: String,
        val kbps: Int,
        val mimeType: String,
        val loudnessDb: Double?,
        val clientName: String,
        /** InnerTubeX's catalog id for the exact client variant; what exclusions key on. */
        val profileId: String,
        val headers: Map<String, String>,
    )

    /**
     * Where the remote player config is remembered between runs — the phone's
     * SharedPreferences, a properties file on the desktop.
     */
    interface ConfigStore {
        fun getString(key: String): String?
        fun getLong(key: String): Long?
        fun putString(key: String, value: String)
        fun putLong(key: String, value: Long)
    }

    /** Whether InnerTubeX's DEBUG lines reach the track log — the phone's debug builds. */
    @Volatile
    var debugLogging: Boolean = false

    private var prefs: ConfigStore? = null
    private var poTokens: TokenProvider? = null
    private var playerDir: File? = null
    private var warmup: Job? = null
    private val warmState = MutableStateFlow(Warm.IDLE)

    private enum class Warm { IDLE, RUNNING, READY, FAILED }

    /**
     * @param filesDir where the preprocessed EJS players are kept.
     * @param poTokenProvider the platform's BotGuard minter — the phone's
     *   WebView one. Null where there is none, which leaves the clients that
     *   need a PoToken out of the catalog.
     */
    fun init(filesDir: File, store: ConfigStore, poTokenProvider: TokenProvider?) {
        prefs = store
        poTokens = poTokenProvider
        playerDir = File(filesDir, "innertubex_players").apply { mkdirs() }
        scope.launch {
            cipherService.setPreprocessedPlayerCache(::readPlayer, ::writePlayer)
            warm(0)
        }
    }

    /**
     * The first resolve waits for [warm] instead of paying the cold cost itself.
     * A warm-up that already failed is started again. The wait has a short
     * ceiling: past it, the resolve goes on and the next tap finds the warm-up
     * either finished or running.
     */
    suspend fun awaitReady() {
        val running = warmup
        if (warmState.value != Warm.READY || running == null || running.isCompleted) {
            if (warmState.value != Warm.RUNNING || running == null || !running.isActive) warm(0)
        }
        if (warmState.value == Warm.READY) return
        PlaybackNotices.preparing(true)
        withTimeoutOrNull(WARM_WAIT_MS) {
            warmState.first { it == Warm.READY || it == Warm.FAILED }
        }
        // Still solving. Wait for that one job. Starting extract now would run
        // a second EJS solver beside it, and the two mint URLs YouTube 403s.
        if (warmState.value == Warm.RUNNING) {
            warmState.first { it == Warm.READY || it == Warm.FAILED }
        }
        PlaybackNotices.preparing(false)
    }

    /**
     * Pays the cold costs before a track needs them: the player config, the EJS
     * solver (8.7s in QuickJS when zemer-cipher lacks the player's hash), and the
     * BotGuard WebView behind WEB_REMIX's PoToken. Measured cold, those put an
     * age-restricted track 15-20s from first audio when paid on the play path.
     */
    private fun warm(delayMs: Long) {
        warmup?.cancel()
        warmState.value = Warm.RUNNING
        warmup = scope.launch {
            delay(delayMs)
            warmState.value = Warm.RUNNING
            val start = MonotonicClock.nowMs()
            val ok = runCatching {
                innerTube.cookie = Innertube.cookie
                innerTube.visitorData = Innertube.ensureVisitorData()
                innerTube.locale = YouTubeLocale(gl = Innertube.currentRegion, hl = Innertube.currentLanguage)
                extractor.prewarm()
            }.onFailure { if (it is CancellationException) throw it }
                .onFailure { TrackLog.w(TAG, "InnerTubeX warm-up failed: ${it.message}") }
                .onSuccess { TrackLog.d(TAG, "InnerTubeX warmed in ${MonotonicClock.nowMs() - start}ms") }
                .isSuccess
            warmState.value = if (ok) Warm.READY else Warm.FAILED
            PlaybackNotices.preparing(false)
        }
    }

    /**
     * EJS preprocessed players, kept across processes. Solving a player from
     * scratch is the 8.7s step; a preprocessed one is reused per player version,
     * so only the first launch after YouTube rotates its player pays it.
     */
    private fun readPlayer(key: String): String? =
        playerDir?.let { File(it, key) }?.takeIf { it.isFile }?.readText()

    private fun writePlayer(key: String, value: String?) {
        val dir = playerDir ?: return
        val file = File(dir, key)
        if (value == null) {
            file.delete()
            return
        }
        val tmp = File(dir, "$key.tmp")
        tmp.writeText(value)
        tmp.renameTo(file)
        // Players rotate every few days; only the newest are worth their megabytes.
        dir.listFiles()?.filter { !it.name.endsWith(".tmp") }?.sortedByDescending { it.lastModified() }
            ?.drop(KEPT_PLAYERS)?.forEach { it.delete() }
    }

    /** The installed PoToken provider, or one that can mint nothing when there is not one. */
    private val tokenProvider = object : TokenProvider {
        override val capabilities: TokenProviderCapabilities
            get() = poTokens?.capabilities ?: TokenProviderCapabilities(providers = emptySet(), usesWebView = false)

        override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? =
            poTokens?.getPoToken(videoId, visitorData, cookie)

        override suspend fun close() {
            poTokens?.close()
        }
    }

    private val http = HttpClient(OkHttp) {
        engine { preconfigured = Http.client }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        expectSuccess = false
    }

    private val logger = InnerTubeLogger { event ->
        if (event.level == InnerTubeLogLevel.DEBUG && !debugLogging) return@InnerTubeLogger
        val details = if (event.details.isEmpty()) "" else event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
        val line = "ITX ${event.tag}: ${event.message}$details"
        if (event.level == InnerTubeLogLevel.INFO || event.level == InnerTubeLogLevel.DEBUG) TrackLog.d(TAG, line) else TrackLog.w(TAG, line)
    }

    private val repository = object : PlayerConfigRepository {
        override val enabled: Boolean get() = prefs != null
        override val sourceUrl: String = PLAYER_CONFIG_URL
        override val defaultSourceUrl: String = PLAYER_CONFIG_URL
        override var cachedJson: String
            get() = prefs?.getString("json").orEmpty()
            set(value) { prefs?.putString("json", value) }
        override var cachedAtMs: Long
            get() = prefs?.getLong("cached_at_ms") ?: 0L
            set(value) { prefs?.putLong("cached_at_ms", value) }
        override var cachedSourceUrl: String
            get() = prefs?.getString("source_url").orEmpty()
            set(value) { prefs?.putString("source_url", value) }
        override var cachedEtag: String
            get() = prefs?.getString("etag").orEmpty()
            set(value) { prefs?.putString("etag", value) }
    }

    private val innerTube = InnerTube(http, logger = logger)
    private val remoteStore = RemotePlayerConfigStore(http, repository, logger)
    private val cipherService = YouTubeCipherService(http, remoteStore, logger)
    private val extractor = InnerTubeExtractor(
        configParser = YtConfigParserImpl(http, innerTube, remoteStore, logger),
        cipherService = cipherService,
        innerTube = innerTube,
        tokenProvider = tokenProvider,
        logger = logger,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What each minted URL was, so its fetch can be dressed and its refusal attributed. */
    private val minted = ConcurrentHashMap<String, Extracted>()

    /** Clients refused a track mid-playback, by videoId, and until when. */
    private val excluded = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    /**
     * InnerTubeX picks one format per ask and has no bitrate ceiling of its own:
     * [maxKbps] only chooses between its smallest and best rungs, and
     * [requireM4a] asks for its best AAC-in-MP4.
     *
     * [uploaded] says [videoId] is one of the signed-in user's own uploads. That
     * changes which clients InnerTubeX will use — authenticated ones only, login
     * cookies first, no embedded fallback — see [UploadedTracks].
     *
     * [premium] says the signed-in account has YouTube Premium. For an Opus ask
     * it sends InnerTubeX down the route age-restricted tracks already take:
     * the signed-in clients first, instead of the anonymous direct ask that
     * answers everything else. Only a signed-in client is offered Premium's
     * ~256kbps Opus (itag 774); the anonymous one tops out at itag 251. The
     * ask is then [AudioQuality.HIGH] — highest bitrate wins — since AUTO's
     * scoring would happily settle for 251. Signed out, InnerTubeX ignores it.
     *
     * @return a stream for [videoId], or null when every client InnerTubeX would
     *   try is refused or [skipClients] has ruled it out.
     * @throws com.metrolist.innertubex.extraction.StreamResolveException with a
     *   reason when InnerTubeX exhausted its catalog.
     */
    suspend fun extract(
        videoId: String,
        maxKbps: Int,
        skipClients: Set<String> = emptySet(),
        requireM4a: Boolean = false,
        uploaded: Boolean = false,
        premium: Boolean = false,
        onlyClient: String? = null,
        allowSabr: Boolean = false,
        sabrAttempt: Int = 0,
    ): Extracted? {
        if (onlyClient == null) {
            awaitReady()
            val remembered = rememberedClient()
            if (remembered != null && remembered !in skipClients && remembered !in excludedFor(videoId)) {
                val hit = runCatching {
                    extract(videoId, maxKbps, skipClients, requireM4a, uploaded, premium, remembered, allowSabr)
                }.getOrNull()
                if (hit != null) return hit
            }
        }
        val narrowed = if (onlyClient != null) otherProfileIds(onlyClient) else emptySet()
        // An ordinary track is minted anonymously. The signed-in visitor makes
        // googlevideo 403 the URL; uploads and Premium still use the account.
        val accountPlayback = uploaded || premium
        innerTube.cookie = if (accountPlayback) Innertube.cookie else null
        innerTube.visitorData = if (accountPlayback) {
            Innertube.ensureVisitorData()
        } else {
            Innertube.playbackVisitorData()
        }
        innerTube.locale = YouTubeLocale(gl = Innertube.currentRegion, hl = Innertube.currentLanguage)
        // Premium's rung is ~256kbps; a ceiling under that couldn't keep it.
        val premiumOpus = premium && !requireM4a && maxKbps >= PREMIUM_OPUS_KBPS
        var hints = ContentHints(isUploaded = true.takeIf { uploaded })
            .withPremium(premiumOpus)
            .withStreamCapabilities(allowHls = false, allowSabr = allowSabr, allowBoundedRange = true)
        if (allowSabr) hints = hints.copy(sabrFirst = true)
        val stream = extractor.extract(
            videoId = videoId,
            hints = hints,
            excludedClients = excludedFor(videoId) + skipClients + narrowed,
            audioQuality = when {
                requireM4a -> AudioQuality.MP4
                maxKbps <= LOW_KBPS -> AudioQuality.LOW
                premiumOpus -> AudioQuality.HIGH
                else -> AudioQuality.AUTO
            },
            clientPlaybackNonce = generateClientPlaybackNonce(),
        ) ?: return null
        val bootstrap = stream.sabrBootstrap
        val sabrFile = if (allowSabr && bootstrap != null) {
            try {
                writeSabrAudio(bootstrap)
            } catch (e: SabrNeedsFreshPlayer) {
                if (sabrAttempt >= 1) {
                    TrackLog.w(TAG, "SABR still wanted a fresh player response for $videoId")
                    null
                } else {
                    TrackLog.d(TAG, "SABR asked for a fresh player response; requesting one")
                    return extract(
                        videoId, maxKbps, skipClients, requireM4a, uploaded, premium,
                        onlyClient, allowSabr, sabrAttempt + 1,
                    )
                }
            }
        } else {
            null
        }
        // A sabr: URL is not a file and not an HTTP request. Probing it crashes OkHttp.
        val direct = stream.audioUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val url = sabrFile ?: direct ?: return null
        val mime = stream.mimeType.orEmpty()
        val headers = stream.headers.ifEmpty {
            if (url.startsWith("file:")) emptyMap() else PlayerClient.forStreamUrl(url).mediaHeaders()
        }
        val extracted = Extracted(
            videoId = videoId,
            url = url,
            kbps = (stream.bitrate ?: 0) / 1000,
            mimeType = if (stream.codecs.isNullOrBlank()) mime else "$mime; codecs=\"${stream.codecs}\"",
            loudnessDb = stream.loudnessDb,
            clientName = stream.clientName,
            profileId = stream.profileId,
            headers = headers,
        )
        if (minted.size >= MAX_REMEMBERED) minted.clear()
        minted[extracted.url] = extracted
        return extracted
    }

    /**
     * Direct googlevideo URLs for this player are refused. SABR is a different
     * transport: pull its audio to a file the player can open, instead of
     * skipping the client that offered it.
     */
    private suspend fun writeSabrAudio(bootstrap: com.metrolist.innertubex.sabr.SabrBootstrap): String? {
        val root = playerDir?.parentFile ?: return null
        val dir = File(root, "sabr").apply { mkdirs() }
        val tmp = File(dir, "${bootstrap.videoId}.part")
        val done = File(dir, "${bootstrap.videoId}.audio")
        return try {
            tmp.outputStream().use { sink ->
                SabrAudioStream(http, bootstrap).bytes().collect { chunk -> sink.write(chunk) }
            }
            if (tmp.length() < 1024) {
                tmp.delete()
                null
            } else {
                if (done.exists()) done.delete()
                tmp.renameTo(done)
                TrackLog.d(TAG, "SABR wrote ${done.length()} bytes for ${bootstrap.videoId}")
                done.toURI().toString()
            }
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: SabrNeedsFreshPlayer) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            val reload = e is SabrProtocolException && e.kind == SabrFailureKind.RELOAD_PLAYER ||
                e.message?.contains("fresh player response", ignoreCase = true) == true
            if (reload) throw SabrNeedsFreshPlayer()
            TrackLog.w(TAG, "SABR fallback failed: ${e.message}")
            null
        }
    }

    /** SABR asked for another player response. The caller fetches one and tries once more. */
    private class SabrNeedsFreshPlayer : Exception()

    /** A music video's picture: a video-only stream, and the identity its fetch has to wear. */
    class VideoStream(
        val videoId: String,
        val url: String,
        val width: Int,
        val height: Int,
        val mimeType: String,
        val headers: Map<String, String>,
        /** Every height this video is offered at, largest first — what a quality picker lists. */
        val heights: List<Int>,
        /** InnerTubeX's catalog id for the client that minted [url]; what a refusal skips. */
        val profileId: String,
    ) {
        val aspect: Float get() = if (height > 0) width.toFloat() / height else 0f
    }

    /**
     * The picture for [videoId], at most [maxHeight] tall, for the player to
     * show over its own audio — as the web player's videoDeck does. The audio
     * this resolves alongside is ignored: the main player already has its own.
     *
     * [ageRestricted] matters more for video than for audio: asked for video,
     * InnerTubeX ranks the TV, VR and iOS clients first and WEB_REMIX last, and
     * several of those front-runners can't serve an age-gated video at all, so
     * the request budget goes on `LOGIN_REQUIRED`s. Saying so drops them, puts
     * the signed-in clients first and retries a refused one with a fresh PoToken.
     *
     * @param skipClients profiles refused for this picture already.
     * @return null when InnerTubeX found no video-only stream, which for a
     *   catalogue track (a still image as its "video") is the normal answer.
     * @throws com.metrolist.innertubex.extraction.StreamResolveException with
     *   [AGE_RESTRICTED][com.metrolist.innertubex.extraction.StreamResolveException.Reason.AGE_RESTRICTED]
     *   when every client asked was turned away by the age gate.
     */
    suspend fun extractVideo(
        videoId: String,
        maxHeight: Int,
        skipClients: Set<String> = emptySet(),
        ageRestricted: Boolean = false,
    ): VideoStream? {
        innerTube.cookie = Innertube.cookie
        innerTube.visitorData = Innertube.ensureVisitorData()
        innerTube.locale = YouTubeLocale(gl = Innertube.currentRegion, hl = Innertube.currentLanguage)
        val stream = extractor.extract(
            videoId = videoId,
            hints = ContentHints(
                isAgeRestricted = true.takeIf { ageRestricted },
                wantVideo = true,
                maxVideoHeight = maxHeight,
            ).withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true),
            excludedClients = excludedFor(videoId) + skipClients,
            audioQuality = AudioQuality.LOW,
            clientPlaybackNonce = generateClientPlaybackNonce(),
        ) ?: return null
        // A live broadcast's video-only URL is a window that ends in seconds,
        // like its audio one; no picture beats a frozen one.
        val url = stream.videoUrl?.takeUnless(StreamResolver::isLiveUrl) ?: return null
        val width = stream.videoWidth ?: 0
        val height = stream.videoHeight ?: 0
        val mime = stream.videoMimeType.orEmpty()
        TrackLog.d(
            TAG,
            "video: ${width}x$height $mime ${stream.videoCodecs.orEmpty()} via ${stream.profileId}" +
                if (ageRestricted) " (age-restricted)" else "",
            about = videoId,
        )
        return VideoStream(
            videoId = videoId,
            url = url,
            width = width,
            height = height,
            mimeType = mime,
            headers = stream.headers,
            heights = (stream.availableVideoHeights.orEmpty() + height)
                .filter { it > 0 }
                .distinct()
                .sortedDescending(),
            profileId = stream.profileId,
        )
    }

    /**
     * A picture's URL was refused mid-playback: the same signal [onRefused]
     * acts on for audio, minus the exclusion — the picture keeps its own, so a
     * video-only refusal doesn't bench a client the audio is fine on.
     */
    fun onVideoRefused() {
        scope.launch { runCatching { cipherService.refreshAfterStreamRejection() } }
    }

    private val _sessionGeneration = MutableStateFlow(0)

    /**
     * Bumped on every sign-in and sign-out, so what's remembered against the
     * old session elsewhere (the picture's "no video here" verdicts) can tell
     * it is stale.
     */
    val sessionGeneration: StateFlow<Int> = _sessionGeneration.asStateFlow()

    /** Headers the media fetch for [url] must carry, when InnerTubeX minted it. */
    fun headersFor(url: String): Map<String, String>? = minted[url]?.headers

    /**
     * Records that googlevideo refused [url] mid-playback.
     *
     * @return the videoId it was minted for, or null when InnerTubeX did not mint it.
     */
    fun onRefused(url: String): String? {
        val refused = minted.remove(url) ?: return null
        exclude(refused.videoId, refused.profileId)
        // A refused ciphered URL is the signal the remote cipher config may be stale; it has its own cooldown.
        scope.launch { runCatching { cipherService.refreshAfterStreamRejection() } }
        return refused.videoId
    }

    /** A probe 403 means the cipher config may be stale. Awaited so the next extract uses the refresh. */
    suspend fun refreshCipher() {
        runCatching { cipherService.refreshAfterStreamRejection() }
    }

    fun exclude(videoId: String, profileId: String) {
        TrackLog.w(TAG, "InnerTubeX: $profileId refused $videoId; skipping it for this track", about = videoId)
        excluded.getOrPut(videoId) { ConcurrentHashMap() }[profileId] = MonotonicClock.nowMs() + EXCLUDE_MS
    }

    /** Drops this track's refused clients so a listener retry asks from scratch. */
    fun clearExclusions(videoId: String) {
        excluded.remove(videoId)
    }

    /** The client that last served audio on this phone, tried first next time. */
    fun rememberClient(profileId: String) {
        if (profileId.isBlank()) return
        prefs?.putString(LAST_CLIENT_KEY, profileId)
    }

    private fun rememberedClient(): String? = prefs?.getString(LAST_CLIENT_KEY)?.takeIf { it.isNotBlank() }

    private fun otherProfileIds(keep: String): Set<String> = runCatching {
        PlaybackClientCatalog.options.flatMap { option ->
            PlaybackClientCatalog.profileIds(option.manifest, false) +
                PlaybackClientCatalog.profileIds(option.manifest, true)
        }.filter { it != keep }.toSet()
    }.getOrDefault(emptySet())

    fun onSessionChanged() {
        excluded.clear()
        minted.clear()
        // A new cookie or visitor means a new player config and PoToken binding.
        if (prefs != null) warm(0)
        _sessionGeneration.update { it + 1 }
    }

    private fun excludedFor(videoId: String): Set<String> {
        val entries = excluded[videoId] ?: return emptySet()
        val now = MonotonicClock.nowMs()
        entries.entries.removeAll { it.value <= now }
        return entries.keys.toSet()
    }

    private const val PLAYER_CONFIG_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
    private const val LOW_KBPS = 64
    /** Itag 774, Premium's Opus rung. */
    private const val PREMIUM_OPUS_KBPS = 256
    private const val EXCLUDE_MS = 10 * 60 * 1000L
    private const val MAX_REMEMBERED = 64
    /** How long a tap waits for warm-up before resolving anyway. */
    private const val WARM_WAIT_MS = 8_000L
    private const val KEPT_PLAYERS = 3
    private const val LAST_CLIENT_KEY = "last_profile"
}
