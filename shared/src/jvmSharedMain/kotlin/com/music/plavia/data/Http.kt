package com.music.plavia.data

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One OkHttp client for the whole app.
 *
 * This matters: googlevideo binds a stream URL to the connection context of
 * the `player` request that minted it. If Innertube and ExoPlayer used
 * separate HTTP stacks they could resolve to different addresses (v4 vs v6)
 * and the media fetch would come back 403. Sharing the client keeps DNS,
 * address family and connection pooling identical for both.
 *
 * That sharing has a cost the defaults don't budget for. Every request the
 * app makes — [Innertube]'s player/browse calls, NewPipe's own signature and
 * `next`-endpoint fetches, [ChunkedDataSource][com.music.plavia.playback.ChunkedDataSource]
 * and [AudioCache][com.music.plavia.playback.AudioCache]'s multi-megabyte
 * chunk downloads, and every [StreamResolver] probe — funnels through this
 * one client, and OkHttp's stock [Dispatcher] allows only 5 requests in
 * flight to a single host at a time. A track streaming while its successor
 * pre-caches is two or three of those requests already; a resolve running
 * alongside them queues behind whichever is occupying the rest — invisibly,
 * since a request stuck in OkHttp's queue and a slow server both just look
 * like a request that took several extra seconds. Sized well past anything
 * this app actually drives concurrently, so the queue is never the reason a
 * request was slow.
 */
object Http {

    // ---- Temporary data-usage instrumentation --------------------------
    //
    // Added to answer, with real numbers instead of code-reading, which of
    // this app's network callers actually account for the reported data
    // usage (issue #48): every request funnels through this one client, so
    // a network interceptor here sees every byte the app receives,
    // regardless of which subsystem asked for it. Categorised by host —
    // googlevideo.com is YouTube's own audio bytes (and StreamResolver's
    // probes, distinguishable by their small actual read against a 2MB
    // Range ask), a canvas provider's CDN is motion artwork, youtube.com /
    // youtubei is API/extraction traffic. Grep logcat for "BCDataUsage"
    // during a play session; each line is one response with its actual
    // transferred bytes and the running total for its host.
    //
    // Left in place rather than deleted — the investigation this served is
    // not the last one this app will need — but off by default: flip to
    // true only while actively measuring. Wrapping every response body in a
    // counting source and writing a log line per request is not free, and
    // paying that on every install for a question already answered is the
    // wrong trade.
    private const val USAGE_LOGGING_ENABLED = false
    private const val USAGE_TAG = "BCDataUsage"
    private val usageTotals = ConcurrentHashMap<String, AtomicLong>()

    private class CountingSource(source: Source, private val onClose: (Long) -> Unit) :
        ForwardingSource(source) {
        private var bytes = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read > 0) bytes += read
            return read
        }
        override fun close() {
            super.close()
            onClose(bytes)
        }
    }

    private class CountedBody(
        private val delegate: ResponseBody,
        private val counted: BufferedSource,
    ) : ResponseBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source() = counted
    }

    private val usageInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val body = response.body
        if (body == null) {
            response
        } else {
            val host = request.url.host
            val range = request.header("Range")
            val counting = CountingSource(body.source()) { bytes ->
                val total = usageTotals.computeIfAbsent(host) { AtomicLong() }.addAndGet(bytes)
                DebugLog.d(
                    USAGE_TAG,
                    "$host ${request.method} ${request.url.encodedPath} " +
                        "range=$range status=${response.code} bytes=$bytes total[$host]=$total",
                )
            }.buffer()
            response.newBuilder().body(CountedBody(body, counting)).build()
        }
    }
    // ---- End temporary instrumentation ----------------------------------

    /**
     * Adds the WebDAV Basic credential to requests aimed at the configured
     * server, so both PROPFIND listings and ExoPlayer's media fetches (which
     * share this client via OkHttpDataSource) authenticate the same way.
     *
     * Read off [com.music.plavia.data.webdav.WebDavAuth] rather than prefs
     * so the network layer never touches storage: AppSettings publishes here.
     */
    private val webDavInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        if (request.header("Authorization") == null &&
            com.music.plavia.data.webdav.WebDavAuth.shouldAuthorize(request.url.host)
        ) {
            val header = com.music.plavia.data.webdav.WebDavAuth.authHeader
            if (!header.isNullOrBlank()) {
                return@Interceptor chain.proceed(
                    request.newBuilder().header("Authorization", header).build(),
                )
            }
        }
        chain.proceed(request)
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .addInterceptor(webDavInterceptor)
        .apply { if (USAGE_LOGGING_ENABLED) addNetworkInterceptor(usageInterceptor) }
        .build()

    /**
     * What the default route is called in the player log. The phone sets this
     * to `vpn` while a VPN is the active network. Playback that has not bound
     * a direct network uses [client], which follows that route.
     */
    @Volatile
    var systemRoute: String = "predeterminada"

    private val playbackSocket = RoutingSocketFactory()
    private val playbackDns = RoutingDns()

    @Volatile
    private var directClient: OkHttpClient? = null

    /** True while playback is bound to a network that is not the VPN. */
    val playbackIsDirect: Boolean get() = directClient != null

    /**
     * The client a playback call must use.
     *
     * [client] itself is never rebuilt: library, Listen Together, WebDAV and
     * Cast keep it. While no direct network is bound this returns that same
     * instance. While one is, it returns a client built from it whose socket
     * factory and DNS are the direct network's.
     */
    fun playbackClient(): OkHttpClient = directClient ?: client

    /** `directa` on the bound network, otherwise [systemRoute]. */
    fun playbackVia(): String = if (directClient != null) "directa" else systemRoute

    /**
     * One call factory for ExoPlayer. Each open asks [playbackClient] again,
     * so a song that starts after the direct network arrives does not keep
     * the VPN client captured when the player was created.
     */
    val playbackCalls: Call.Factory = Call.Factory { request -> playbackClient().newCall(request) }

    /**
     * The OkHttp client a long-lived Ktor engine is built on.
     *
     * Ktor copies the client once and keeps the copy. The copy shares this
     * socket factory and DNS, whose targets move when [bindPlayback] runs,
     * so the player requests follow the same network as [playbackClient]
     * without rebuilding the engine or the resolve gate.
     */
    fun playbackEngine(configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient =
        client.newBuilder()
            .socketFactory(playbackSocket)
            .dns(playbackDns)
            .apply(configure)
            .build()

    /**
     * Point playback at a direct network. [socketFactory] and [dns] are that
     * network's, not the process default.
     */
    fun bindPlayback(socketFactory: SocketFactory, dns: Dns) {
        playbackSocket.target = socketFactory
        playbackDns.target = dns
        directClient = client.newBuilder()
            .socketFactory(socketFactory)
            .dns(dns)
            .build()
    }

    /** Drop the direct network. Playback returns to [client]. */
    fun clearPlaybackBinding() {
        directClient = null
        playbackSocket.target = SocketFactory.getDefault()
        playbackDns.target = Dns.SYSTEM
    }

    private class RoutingSocketFactory : SocketFactory() {
        @Volatile
        var target: SocketFactory = getDefault()

        override fun createSocket(): Socket = target.createSocket()

        override fun createSocket(host: String, port: Int): Socket = target.createSocket(host, port)

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            target.createSocket(host, port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): Socket = target.createSocket(host, port)

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            target.createSocket(address, port, localAddress, localPort)
    }

    private class RoutingDns : Dns {
        @Volatile
        var target: Dns = Dns.SYSTEM

        override fun lookup(hostname: String): List<InetAddress> = target.lookup(hostname)
    }
}

/**
 * The network playback asks Android for when it should leave the VPN.
 *
 * The numbers are the platform constants ([android.net.NetworkCapabilities]).
 * Kept here so the JVM test and the phone build the same request: Wi-Fi or
 * mobile data, and not a VPN. Internet is required so a captive portal is
 * not treated as a usable route.
 */
object DirectNetworkRequest {
    const val TRANSPORT_CELLULAR = 0
    const val TRANSPORT_WIFI = 1
    const val NET_CAPABILITY_INTERNET = 12
    const val NET_CAPABILITY_NOT_VPN = 15

    fun applyTo(addTransport: (Int) -> Unit, addCapability: (Int) -> Unit) {
        addTransport(TRANSPORT_WIFI)
        addTransport(TRANSPORT_CELLULAR)
        addCapability(NET_CAPABILITY_NOT_VPN)
        addCapability(NET_CAPABILITY_INTERNET)
    }
}
