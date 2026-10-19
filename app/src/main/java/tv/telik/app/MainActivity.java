package tv.telik.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.net.http.SslError;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * The whole app is this one screen.
 *
 * 1. If a stream link from the previous run is cached, play it right away with the native player.
 * 2. Otherwise (or if it fails) open the page in an invisible-UI WebView, catch the .m3u8 request the
 *    page's own player makes, then close the WebView and play that link with the native hardware player.
 *    In parallel the page HTML (and its player iframes) is fetched directly and searched for an .m3u8 link,
 *    which works even when the box's built-in browser is too old to run the page's scripts.
 * 3. If no link shows up, the page itself stays on screen with everything except the video hidden.
 *
 * Errors, stalls and network loss restart the stream automatically.
 */
public class MainActivity extends Activity {

    private static final String PAGE_URL = "https://telik.live/zhivaya-priroda.html";
    private static final String PAGE_HOST = "telik.live";

    private static final long STALL_TIMEOUT_MS = 30_000;
    private static final long PAGE_RETRY_MS = 5_000;
    private static final long INJECT_PERIOD_MS = 1_500;
    private static final int MAX_RETRIES_SAME_URL = 3;

    private static final String PREFS = "s";
    private static final String KEY_URL = "url";
    private static final String KEY_HEADERS = "hdr";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> badUrls = java.util.Collections.synchronizedSet(new HashSet<>());

    private FrameLayout root;
    private SurfaceView surface;
    private WebView web;
    private ExoPlayer player;

    private String streamUrl;
    private Map<String, String> streamHeaders = new HashMap<>();
    private int failCount;
    private boolean started;
    private ConnectivityManager.NetworkCallback netCallback;
    private boolean netLost;
    private boolean sniffing;
    private int scrapeGen;
    private String defaultUa;

    // Diagnostics: shown in a corner only when nothing has played for a while.
    private static final long DIAG_DELAY_MS = 15_000;
    private TextView diag;
    private boolean playingOk;
    private boolean diagHidden;
    private long notPlayingSince;
    private String stage = "старт";
    private final LinkedList<String> lastErrors = new LinkedList<>();
    private volatile String frameSrc = "-";
    private volatile int patchedPages;
    private volatile byte[] polyfill;
    private volatile StringBuffer scrapeTrace;
    private final List<String> altUrls = new ArrayList<>();
    private static volatile String tlsProblem;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setKeepScreenOn(true);
        setContentView(root);
        hideSystemUi();

        diag = new TextView(this);
        diag.setTextColor(0xFFAAAAAA);
        diag.setBackgroundColor(0xAA000000);
        diag.setTextSize(12);
        diag.setPadding(16, 8, 16, 8);
        diag.setVisibility(View.GONE);
        root.addView(diag, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));

        installLenientTls();
        try {
            defaultUa = WebSettings.getDefaultUserAgent(this);
        } catch (RuntimeException e) {
            defaultUa = null;
        }

        loadCachedStream();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        setPlaying(false);
        main.postDelayed(diagLoop, 3_000);
        registerNetworkCallback();
        if (streamUrl != null) {
            startPlayer();
        } else {
            startSniffing();
        }
    }

    @Override
    protected void onStop() {
        started = false;
        unregisterNetworkCallback();
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        destroyWebView();
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                // OK on the remote = restart the stream (handy if the picture ever freezes).
                if (player != null) {
                    failCount = 0;
                    retryPlayerNow();
                    return true;
                }
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_DPAD_UP:
                // Show/hide the diagnostics line.
                diagHidden = !diagHidden;
                if (diagHidden) diag.setVisibility(View.GONE);
                else showDiag();
                return true;
            case KeyEvent.KEYCODE_SEARCH:
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ---------------------------------------------------------------- native player

    private void startPlayer() {
        if (!started || streamUrl == null) return;
        sniffing = false;
        scrapeGen++;
        destroyWebView();
        releasePlayer();
        stage = "плеер: подключение к потоку";

        if (surface == null) {
            surface = new SurfaceView(this);
            root.addView(surface, 0, matchParent());
        }

        String ua = streamHeaders.get("User-Agent");
        if (ua == null) ua = defaultUa;
        Map<String, String> extra = new HashMap<>(streamHeaders);
        extra.remove("User-Agent");
        String cookies = CookieManager.getInstance().getCookie(streamUrl);
        if (cookies != null && !extra.containsKey("Cookie")) extra.put("Cookie", cookies);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
                .setDefaultRequestProperties(extra);
        if (ua != null) http.setUserAgent(ua);

        // Small buffers: low RAM use on a weak box, fast start.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
                .build();

        // The box outputs 1080p at best; never pick a heavier variant than that.
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(this);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setMaxVideoSize(1920, 1080)
                .setExceedRendererCapabilitiesIfNecessary(true));

        // Hardware MediaCodec decoders; fall back to another decoder if the first one fails.
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);

        HlsMediaSource.Factory hls = new HlsMediaSource.Factory(http).setAllowChunklessPreparation(true);

        player = new ExoPlayer.Builder(this, renderers, hls)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(playerListener);
        player.setMediaItem(MediaItem.fromUri(streamUrl));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            main.removeCallbacks(stallCheck);
            if (state == Player.STATE_READY) {
                failCount = 0;
                stage = "плеер: воспроизведение";
                setPlaying(true);
                saveCachedStream();
            } else if (state == Player.STATE_BUFFERING) {
                stage = "плеер: буферизация";
                setPlaying(false);
                main.postDelayed(stallCheck, STALL_TIMEOUT_MS);
            } else if (state == Player.STATE_ENDED) {
                // A live stream never ends: this link was probably an ad or a preview. Find another.
                forgetStream(true);
                startSniffing();
            }
        }

        @Override
        public void onVideoSizeChanged(VideoSize size) {
            fitSurface(size);
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            main.removeCallbacks(stallCheck);
            setPlaying(false);
            Throwable c = error.getCause();
            noteError("плеер " + error.getErrorCodeName() + (c != null ? ": " + c : ""));
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {
                player.seekToDefaultPosition();
                player.prepare();
                return;
            }
            failCount++;
            boolean linkRejected = isHttpRejected(error);
            if ((linkRejected || failCount > MAX_RETRIES_SAME_URL) && !altUrls.isEmpty()) {
                // Try the backup server of the same stream first.
                streamUrl = altUrls.remove(0);
                failCount = 0;
                main.postDelayed(MainActivity.this::startPlayer, 1_000);
            } else if (linkRejected || failCount > MAX_RETRIES_SAME_URL) {
                // Link probably expired: get a fresh one from the page.
                forgetStream(false);
                main.postDelayed(MainActivity.this::startSniffing, linkRejected ? 0 : 2_000);
            } else {
                main.postDelayed(MainActivity.this::retryPlayerNow, Math.min(2_000L * failCount, 10_000L));
            }
        }
    };

    private final Runnable stallCheck = () -> {
        if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING) {
            noteError("поток завис в буферизации");
            failCount++;
            if (failCount > MAX_RETRIES_SAME_URL) {
                forgetStream(false);
                startSniffing();
            } else {
                retryPlayerNow();
            }
        }
    };

    /** Keep the picture's aspect ratio: letterbox inside the screen instead of stretching. */
    private void fitSurface(VideoSize size) {
        if (surface == null || size.width <= 0 || size.height <= 0) return;
        int sw = root.getWidth(), sh = root.getHeight();
        if (sw <= 0 || sh <= 0) return;
        float videoRatio = size.width * size.pixelWidthHeightRatio / size.height;
        int w = sw, h = Math.round(sw / videoRatio);
        if (h > sh) {
            h = sh;
            w = Math.round(sh * videoRatio);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, android.view.Gravity.CENTER);
        surface.setLayoutParams(lp);
    }

    private static boolean isHttpRejected(PlaybackException error) {
        Throwable cause = error.getCause();
        if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
            int code = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
            return code == 401 || code == 403 || code == 404 || code == 410;
        }
        return false;
    }

    private void retryPlayerNow() {
        if (!started) return;
        if (player == null) {
            startPlayer();
            return;
        }
        player.stop();
        player.seekToDefaultPosition();
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void releasePlayer() {
        main.removeCallbacks(stallCheck);
        if (player != null) {
            player.removeListener(playerListener);
            player.release();
            player = null;
        }
    }

    // ---------------------------------------------------------------- page + link sniffing

    @SuppressLint("SetJavaScriptEnabled")
    private void startSniffing() {
        if (!started) return;
        releasePlayer();
        setPlaying(false);
        sniffing = true;
        stage = "поиск ссылки на поток";
        if (surface != null) {
            root.removeView(surface);
            surface = null;
        }
        scrapeAsync();

        if (web == null) {
            try {
                web = new WebView(this);
            } catch (RuntimeException e) {
                // No usable system WebView on this box: rely on the HTML search only.
                noteError("WebView не создаётся: " + e);
                main.postDelayed(MainActivity.this::retrySniffIfIdle, 20_000);
                return;
            }
            web.setBackgroundColor(Color.BLACK);
            web.setVerticalScrollBarEnabled(false);
            web.setHorizontalScrollBarEnabled(false);
            web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setSupportZoom(false);
            s.setBuiltInZoomControls(false);
            s.setDisplayZoomControls(false);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setSupportMultipleWindows(false);
            s.setJavaScriptCanOpenWindowsAutomatically(false);
            s.setBlockNetworkImage(true); // pictures on the page are useless here
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

            web.addJavascriptInterface(new JsBridge(), "TK");
            web.setWebViewClient(webClient);
            web.setWebChromeClient(new WebChromeClient() {
                @Override
                public View getVideoLoadingProgressView() {
                    return new View(MainActivity.this);
                }

                @Override
                public boolean onConsoleMessage(ConsoleMessage m) {
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        noteError("JS: " + m.message() + " @" + m.lineNumber());
                    }
                    return true;
                }
            });
            root.addView(web, 0, matchParent());
        }
        web.loadUrl(PAGE_URL);
        main.removeCallbacks(injectLoop);
        main.postDelayed(injectLoop, INJECT_PERIOD_MS);
    }

    private final WebViewClient webClient = new WebViewClient() {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            // Runs on a WebView background thread.
            String url = request.getUrl().toString();
            if (isStreamUrl(url)) {
                final Map<String, String> headers = pickHeaders(request.getRequestHeaders());
                main.post(() -> onStreamFound(url, headers));
                return null;
            }
            if (wantsHtml(request)) {
                // Page or player frame: add the missing-functions script before the site's own scripts run.
                try {
                    return fetchHtmlWithPolyfill(url, request.getRequestHeaders());
                } catch (Exception e) {
                    return null; // let the WebView load it normally
                }
            }
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return request.isForMainFrame() && isForeign(request.getUrl());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return isForeign(Uri.parse(url));
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // Old boxes often have outdated root certificates or a wrong clock. This app only shows a public
            // video page, so accept the certificate rather than show nothing.
            noteError("SSL " + error.getPrimaryError() + " " + error.getUrl());
            handler.proceed();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            injectFullscreenVideo();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                noteError("страница: " + error.getErrorCode() + " " + error.getDescription());
                schedulePageReload();
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (Build.VERSION.SDK_INT < 23) {
                noteError("страница: " + errorCode + " " + description);
                schedulePageReload();
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // WebView crashed or was killed for memory: rebuild it instead of crashing the app.
            noteError("WebView упал");
            destroyWebView();
            main.postDelayed(MainActivity.this::startSniffing, PAGE_RETRY_MS);
            return true;
        }
    };

    private void onStreamFound(String url, Map<String, String> headers) {
        if (!started || !sniffing || badUrls.contains(url)) return;
        streamUrl = url;
        streamHeaders = headers;
        failCount = 0;
        startPlayer();
    }

    private void schedulePageReload() {
        main.removeCallbacks(reloadPage);
        main.postDelayed(reloadPage, PAGE_RETRY_MS);
    }

    private final Runnable reloadPage = () -> {
        if (started && web != null) web.loadUrl(PAGE_URL);
    };

    private final Runnable injectLoop = new Runnable() {
        @Override
        public void run() {
            if (!started || web == null) return;
            injectFullscreenVideo();
            main.postDelayed(this, INJECT_PERIOD_MS);
        }
    };

    /**
     * Fallback when no direct link was caught: stretch the page's video (or its player iframe) over the
     * whole screen, hide everything else, start playback.
     */
    private void injectFullscreenVideo() {
        if (web == null) return;
        web.evaluateJavascript(JS_FULLSCREEN, null);
    }

    private static final String JS_FULLSCREEN = "(function(){try{"
            + "var d=document;if(!d.body)return;"
            + "if(!d.getElementById('__tk')){var s=d.createElement('style');s.id='__tk';"
            + "s.textContent='html,body{background:#000!important;overflow:hidden!important;margin:0!important}"
            + "body *{visibility:hidden!important}"
            + ".__tkv,.__tkv *{visibility:visible!important}"
            + ".__tkv{position:fixed!important;left:0!important;top:0!important;width:100vw!important;"
            + "height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;"
            + "border:0!important;z-index:2147483647!important;background:#000!important;transform:none!important}"
            + "video.__tkv{object-fit:contain!important}';"
            + "(d.head||d.body).appendChild(s);}"
            + "var t=d.querySelector('video');"
            + "if(!t){var f=d.querySelectorAll('iframe'),a=0;for(var i=0;i<f.length;i++){var r=f[i].getBoundingClientRect();"
            + "if(r.width*r.height>a){a=r.width*r.height;t=f[i];}}}"
            + "if(t&&!t.classList.contains('__tkv')){var o=d.querySelectorAll('.__tkv');for(var j=0;j<o.length;j++)o[j].classList.remove('__tkv');"
            + "t.classList.add('__tkv');"
            + "for(var p=t.parentElement;p&&p!==d.body;p=p.parentElement){p.style.transform='none';p.style.filter='none';"
            + "p.style.contain='none';p.style.willChange='auto';}}"
            + "if(t&&t.tagName==='VIDEO'){t.controls=false;if(t.paused){var pr=t.play();"
            + "if(pr&&pr.catch)pr.catch(function(){t.muted=true;t.play();});}}"
            + "if(window.TK)TK.playing(!!(t&&t.tagName==='VIDEO'&&!t.paused&&t.readyState>2),t?t.tagName:'',t?(t.currentSrc||t.src||''):'');"
            + "}catch(e){}})();";

    private void destroyWebView() {
        main.removeCallbacks(injectLoop);
        main.removeCallbacks(reloadPage);
        if (web != null) {
            WebView w = web;
            web = null;
            root.removeView(w);
            w.stopLoading();
            w.setWebViewClient(new WebViewClient());
            w.loadUrl("about:blank");
            w.destroy();
        }
    }

    private static boolean isStreamUrl(String url) {
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        String path = q >= 0 ? u.substring(0, q) : u;
        return path.endsWith(".m3u8") || path.contains(".m3u8/") || (q >= 0 && u.indexOf(".m3u8", q) > 0 && !path.endsWith(".js"));
    }

    private static boolean isForeign(Uri uri) {
        String host = uri.getHost();
        if (host == null) return true;
        host = host.toLowerCase();
        return !(host.equals(PAGE_HOST) || host.endsWith("." + PAGE_HOST));
    }

    private static Map<String, String> pickHeaders(Map<String, String> in) {
        Map<String, String> out = new HashMap<>();
        if (in == null) return out;
        for (Map.Entry<String, String> e : in.entrySet()) {
            String k = e.getKey();
            if (k == null || e.getValue() == null) continue;
            String lk = k.toLowerCase();
            if (lk.equals("user-agent")) out.put("User-Agent", e.getValue());
            else if (lk.equals("referer")) out.put("Referer", e.getValue());
            else if (lk.equals("origin")) out.put("Origin", e.getValue());
            else if (lk.equals("cookie")) out.put("Cookie", e.getValue());
            else if (lk.equals("authorization")) out.put("Authorization", e.getValue());
        }
        return out;
    }

    private void retrySniffIfIdle() {
        if (started && sniffing && player == null) startSniffing();
    }

    /** Called from the injected page script (WebView fallback mode). */
    private final class JsBridge {
        @JavascriptInterface
        public void playing(boolean isPlaying, String what, String src) {
            main.post(() -> {
                if (!sniffing) return;
                stage = "страница: " + (what == null || what.isEmpty() ? "видео не найдено" : what);
                if (src != null && !src.isEmpty()) frameSrc = src;
                setPlaying(isPlaying);
            });
        }
    }

    private static final Pattern HEAD_TAG = Pattern.compile("<head\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<html\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private static boolean wantsHtml(WebResourceRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return false;
        String scheme = request.getUrl().getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        Map<String, String> h = request.getRequestHeaders();
        if (h == null) return false;
        for (Map.Entry<String, String> e : h.entrySet()) {
            if ("accept".equalsIgnoreCase(e.getKey())) {
                return e.getValue() != null && e.getValue().contains("text/html");
            }
        }
        return false;
    }

    private byte[] polyfillTag() throws Exception {
        if (polyfill == null) {
            InputStream in = getAssets().open("polyfill.js");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write("<script>".getBytes("ISO-8859-1"));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.write("</script>".getBytes("ISO-8859-1"));
            polyfill = out.toByteArray();
        }
        return polyfill;
    }

    /** Downloads an HTML document itself and inserts the polyfill script right after <head>. */
    private WebResourceResponse fetchHtmlWithPolyfill(String url, Map<String, String> reqHeaders) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(15_000);
        if (reqHeaders != null) {
            for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue() == null) continue;
                String lk = k.toLowerCase();
                if (lk.equals("accept-encoding") || lk.equals("range") || lk.equals("host")) continue;
                c.setRequestProperty(k, e.getValue());
            }
        }
        CookieManager cm = CookieManager.getInstance();
        String cookie = cm.getCookie(url);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);

        int code = c.getResponseCode();
        if (code == 401 || code == 403) {
            c.disconnect();
            String referer = null;
            String ua = null;
            if (reqHeaders != null) for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                if ("referer".equalsIgnoreCase(e.getKey())) referer = e.getValue();
                if ("user-agent".equalsIgnoreCase(e.getKey())) ua = e.getValue();
            }
            c = openLikeBrowser(url, referer, ua, false);
            code = c.getResponseCode();
        }
        String type = c.getContentType();
        if (code != 200 || type == null || !type.toLowerCase().contains("text/html")) {
            // Redirects, errors and non-HTML are left to the WebView itself.
            c.disconnect();
            return null;
        }
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("set-cookie")) {
                for (String v : e.getValue()) cm.setCookie(url, v);
            }
        }
        String charset = null;
        Matcher cs = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(type);
        if (cs.find()) charset = cs.group(1);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        InputStream in = c.getInputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) body.write(buf, 0, n);
        c.disconnect();
        byte[] bytes = body.toByteArray();

        // Work on bytes (Latin-1 maps 1:1) so the page's own encoding is never touched.
        String latin = new String(bytes, "ISO-8859-1");
        int at = 0;
        Matcher m = HEAD_TAG.matcher(latin);
        if (m.find()) {
            at = m.end();
        } else {
            m = HTML_TAG.matcher(latin);
            if (m.find()) at = m.end();
        }
        // The WebView loads player frames with the right cookies/referer: look for the stream link right here.
        final String[] found = findStreams(latin, url);
        if (found != null) {
            String ua = null;
            if (reqHeaders != null) for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                if ("user-agent".equalsIgnoreCase(e.getKey())) ua = e.getValue();
            }
            final String fua = ua != null ? ua : defaultUa;
            main.post(() -> onStreamsFound(found, fua));
        }

        byte[] tag = polyfillTag();
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + tag.length);
        out.write(bytes, 0, at);
        out.write(tag);
        out.write(bytes, at, bytes.length - at);
        patchedPages++;

        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache");
        return new WebResourceResponse("text/html", charset, 200, "OK", headers,
                new ByteArrayInputStream(out.toByteArray()));
    }

    // ---------------------------------------------------------------- direct HTML search (no JS needed)

    private static final Pattern M3U8 = Pattern.compile(
            "(?:https?:)?//[^\"'\\s<>()\\\\]+?\\.m3u8[^\"'\\s<>()\\\\]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT = Pattern.compile(
            "<script[^>]+?src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern IFRAME = Pattern.compile(
            "<iframe[^>]+?src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private void scrapeAsync() {
        final int gen = ++scrapeGen;
        final String ua = defaultUa;
        scrapeTrace = new StringBuffer();
        Thread t = new Thread(() -> {
            String[] found = null;
            try {
                found = scrape(PAGE_URL, null, ua, 0);
            } catch (Exception e) {
                final String msg = "HTML: " + e;
                main.post(() -> noteError(msg));
            }
            if (found == null) return;
            final String[] f = found;
            main.post(() -> {
                if (gen == scrapeGen) onStreamsFound(f, ua);
            });
        }, "scrape");
        t.setDaemon(true);
        t.start();
    }

    /** found = {pageItWasFoundOn, url1, url2, ...}. Plays url1, keeps the rest as backups. */
    private void onStreamsFound(String[] found, String ua) {
        Map<String, String> h = new HashMap<>();
        h.put("Referer", found[0]);
        Uri r = Uri.parse(found[0]);
        h.put("Origin", r.getScheme() + "://" + r.getHost());
        // Stream servers get the same modern browser identity that the player page accepted.
        h.put("User-Agent", MODERN_UA);
        if (!started || !sniffing) return;
        altUrls.clear();
        for (int i = 2; i < found.length; i++) altUrls.add(found[i]);
        onStreamFound(found[1], h);
    }

    /** Finds .m3u8 links in a page; " or "-separated backups (Playerjs style) come along too. */
    private String[] findStreams(String text, String pageUrl) {
        text = text.replace("\\/", "/");
        List<String> out = new ArrayList<>();
        out.add(pageUrl);
        Matcher m = M3U8.matcher(text);
        while (m.find()) {
            try {
                String u = resolve(pageUrl, m.group());
                if (!badUrls.contains(u) && !out.contains(u)) out.add(u);
            } catch (Exception ignored) {
            }
        }
        return out.size() > 1 ? out.toArray(new String[0]) : null;
    }

    /** Returns {pageItWasFoundOn, streamUrl, backups...} or null. Follows player iframes three levels deep. */
    private String[] scrape(String pageUrl, String referer, String ua, int depth) throws Exception {
        String html;
        try {
            html = fetch(pageUrl, referer, ua);
            trace(Uri.parse(pageUrl).getHost() + " ok");
        } catch (Exception e) {
            trace(Uri.parse(pageUrl).getHost() + " " + e.getMessage());
            throw e;
        }
        String[] found = findStreams(html, pageUrl);
        if (found != null) return found;
        if (depth < 3) {
            Matcher f = IFRAME.matcher(html);
            while (f.find()) {
                String child = resolve(pageUrl, f.group(1).replace("&amp;", "&"));
                if (!child.startsWith("http")) continue;
                try {
                    String[] r = scrape(child, pageUrl, ua, depth + 1);
                    if (r != null) return r;
                } catch (Exception ignored) {
                }
            }
        }
        // Player settings sometimes live in a separate script file (only on player frames, not the big page).
        if (depth == 0) return null;
        Matcher sc = SCRIPT.matcher(html);
        int scripts = 0;
        while (sc.find() && scripts < 4) {
            String src = resolve(pageUrl, sc.group(1).replace("&amp;", "&"));
            String ls = src.toLowerCase();
            if (!ls.startsWith("http") || ls.contains("jquery") || ls.contains("google") || ls.contains("yandex")
                    || ls.contains("gstatic") || ls.contains("counter")) continue;
            scripts++;
            try {
                String[] r = findStreams(fetch(src, pageUrl, ua), pageUrl);
                if (r != null) return r;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void trace(String step) {
        StringBuffer t = scrapeTrace;
        if (t != null && t.length() < 300) t.append(t.length() > 0 ? " → " : "").append(step);
    }

    private static String resolve(String base, String url) throws Exception {
        if (url.startsWith("//")) return Uri.parse(base).getScheme() + ":" + url;
        return new URL(new URL(base), url).toString();
    }

    /** A current desktop Chrome: the player server answers 403 to the box's 2016 browser. */
    private static final String MODERN_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    /**
     * Opens a page, trying progressively more "real browser" request headers if the server says 401/403.
     * variant 0: as given; 1: modern Chrome as an iframe with origin referer; 2: same with full referer.
     */
    private HttpURLConnection openLikeBrowser(String url, String referer, String ua, boolean followRedirects)
            throws Exception {
        HttpURLConnection c = null;
        for (int v = 0; v < 3; v++) {
            if (v > 0 && referer == null) break;
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setInstanceFollowRedirects(followRedirects);
            if (v == 0) {
                if (ua != null) c.setRequestProperty("User-Agent", ua);
                if (referer != null) c.setRequestProperty("Referer", referer);
                c.setRequestProperty("Accept", "text/html,*/*");
            } else {
                Uri ref = Uri.parse(referer);
                c.setRequestProperty("User-Agent", MODERN_UA);
                c.setRequestProperty("Referer", v == 1 ? ref.getScheme() + "://" + ref.getHost() + "/" : referer);
                c.setRequestProperty("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
                c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
                c.setRequestProperty("Upgrade-Insecure-Requests", "1");
                c.setRequestProperty("Sec-Fetch-Dest", "iframe");
                c.setRequestProperty("Sec-Fetch-Mode", "navigate");
                c.setRequestProperty("Sec-Fetch-Site", "cross-site");
                c.setRequestProperty("Sec-Ch-Ua", "\"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not?A_Brand\";v=\"99\"");
                c.setRequestProperty("Sec-Ch-Ua-Mobile", "?0");
                c.setRequestProperty("Sec-Ch-Ua-Platform", "\"Windows\"");
            }
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
            int code = c.getResponseCode();
            if (code != 401 && code != 403) {
                if (v > 0) trace("вход как Chrome/" + v);
                return c;
            }
            if (v < 2) c.disconnect();
        }
        return c;
    }

    private String fetch(String url, String referer, String ua) throws Exception {
        HttpURLConnection c = openLikeBrowser(url, referer, ua, true);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new java.io.IOException("HTTP " + code + " " + url);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 3_000_000) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /**
     * Old Android boxes often have outdated root certificates (Let's Encrypt) or a wrong clock, so HTTPS fails
     * with nothing on screen. Use the normal check, but if it fails, note it and continue anyway: the app only
     * fetches a public video page and stream, nothing private is sent.
     */
    private static void installLenientTls() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            final X509TrustManager sys = (X509TrustManager) tmf.getTrustManagers()[0];
            X509TrustManager tm = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    sys.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    try {
                        sys.checkServerTrusted(chain, authType);
                    } catch (CertificateException e) {
                        tlsProblem = String.valueOf(e.getMessage());
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return sys.getAcceptedIssuers();
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- diagnostics

    private void setPlaying(boolean ok) {
        if (ok == playingOk && (ok || notPlayingSince != 0)) return;
        playingOk = ok;
        if (ok) {
            notPlayingSince = 0;
            diag.setVisibility(View.GONE);
        } else {
            notPlayingSince = System.currentTimeMillis();
        }
    }

    private void noteError(String e) {
        if (e.length() > 200) e = e.substring(0, 200);
        lastErrors.remove(e);
        lastErrors.addFirst(e);
        while (lastErrors.size() > 3) lastErrors.removeLast();
        if (diag.getVisibility() == View.VISIBLE) showDiag();
    }

    private final Runnable diagLoop = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            if (!playingOk && !diagHidden && notPlayingSince != 0
                    && System.currentTimeMillis() - notPlayingSince > DIAG_DELAY_MS) {
                showDiag();
            }
            main.postDelayed(this, 3_000);
        }
    };

    private void showDiag() {
        String chrome = "?";
        if (defaultUa != null) {
            Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(defaultUa);
            if (m.find()) chrome = m.group(1);
        }
        String url = streamUrl == null ? "-" : (streamUrl.length() > 120 ? streamUrl.substring(0, 120) + "…" : streamUrl);
        String text = "TelikTV " + BuildConfig.VERSION_NAME
                + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + " · WebView " + chrome
                + "\nЭтап: " + stage
                + "\nОшибки: " + (lastErrors.isEmpty() ? "-" : android.text.TextUtils.join("\n   ", lastErrors))
                + "\nПлеер сайта: " + (frameSrc.length() > 120 ? frameSrc.substring(0, 120) + "…" : frameSrc)
                + " · исправлено страниц: " + patchedPages
                + (tlsProblem != null ? "\nСертификат: " + tlsProblem : "")
                + "\nПоиск в HTML: " + (scrapeTrace == null || scrapeTrace.length() == 0 ? "-" : scrapeTrace)
                + "\nСсылка: " + url
                + "\n(кнопка Menu или Вверх скрывает/показывает эту надпись)";
        diag.setText(text);
        diag.setVisibility(View.VISIBLE);
        diag.bringToFront();
    }

    // ---------------------------------------------------------------- cached link

    private void loadCachedStream() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        streamUrl = p.getString(KEY_URL, null);
        streamHeaders = decodeHeaders(p.getString(KEY_HEADERS, ""));
    }

    private void saveCachedStream() {
        if (streamUrl == null) return;
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_URL, streamUrl)
                .putString(KEY_HEADERS, encodeHeaders(streamHeaders))
                .apply();
    }

    private void forgetStream(boolean markBad) {
        if (markBad && streamUrl != null) badUrls.add(streamUrl);
        streamUrl = null;
        streamHeaders = new HashMap<>();
        failCount = 0;
        altUrls.clear();
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static String encodeHeaders(Map<String, String> h) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : h.entrySet()) {
            sb.append(e.getKey()).append('\u0001').append(e.getValue()).append('\u0002');
        }
        return sb.toString();
    }

    private static Map<String, String> decodeHeaders(String s) {
        Map<String, String> out = new HashMap<>();
        for (String line : s.split("\u0002")) {
            int i = line.indexOf('\u0001');
            if (i > 0) out.put(line.substring(0, i), line.substring(i + 1));
        }
        return out;
    }

    // ---------------------------------------------------------------- network

    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < 24 || netCallback != null) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                main.post(MainActivity.this::onNetworkBack);
            }

            @Override
            public void onLost(Network network) {
                main.post(() -> netLost = true);
            }
        };
        try {
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (RuntimeException ignored) {
            netCallback = null;
        }
    }

    private void unregisterNetworkCallback() {
        if (netCallback == null) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            if (cm != null) cm.unregisterNetworkCallback(netCallback);
        } catch (RuntimeException ignored) {
        }
        netCallback = null;
    }

    /** Internet came back: restart right away instead of waiting for the next retry. */
    private void onNetworkBack() {
        // registerDefaultNetworkCallback reports the current network right away; only react after a loss.
        if (!started || !netLost) return;
        netLost = false;
        if (player != null) {
            if (player.getPlayerError() != null || player.getPlaybackState() == Player.STATE_IDLE
                    || player.getPlaybackState() == Player.STATE_BUFFERING) {
                failCount = 0;
                retryPlayerNow();
            }
        } else if (web != null) {
            main.removeCallbacks(reloadPage);
            web.loadUrl(PAGE_URL);
        }
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
