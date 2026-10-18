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
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The whole app is this one screen.
 *
 * 1. If a stream link from the previous run is cached, play it right away with the native player.
 * 2. Otherwise (or if it fails) open the page in an invisible-UI WebView, catch the .m3u8 request the
 *    page's own player makes, then close the WebView and play that link with the native hardware player.
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
    private final Set<String> badUrls = new HashSet<>();

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

        loadCachedStream();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
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
        destroyWebView();
        releasePlayer();

        if (surface == null) {
            surface = new SurfaceView(this);
            root.addView(surface, 0, matchParent());
        }

        String ua = streamHeaders.get("User-Agent");
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
                saveCachedStream();
            } else if (state == Player.STATE_BUFFERING) {
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
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {
                player.seekToDefaultPosition();
                player.prepare();
                return;
            }
            failCount++;
            boolean linkRejected = isHttpRejected(error);
            if (linkRejected || failCount > MAX_RETRIES_SAME_URL) {
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
        if (surface != null) {
            root.removeView(surface);
            surface = null;
        }

        if (web == null) {
            web = new WebView(this);
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

            web.setWebViewClient(webClient);
            web.setWebChromeClient(new WebChromeClient() {
                @Override
                public View getVideoLoadingProgressView() {
                    return new View(MainActivity.this);
                }
            });
            root.addView(web, matchParent());
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
        public void onPageFinished(WebView view, String url) {
            injectFullscreenVideo();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) schedulePageReload();
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (Build.VERSION.SDK_INT < 23) schedulePageReload();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // WebView crashed or was killed for memory: rebuild it instead of crashing the app.
            destroyWebView();
            main.postDelayed(MainActivity.this::startSniffing, PAGE_RETRY_MS);
            return true;
        }
    };

    private void onStreamFound(String url, Map<String, String> headers) {
        if (!started || web == null || badUrls.contains(url)) return;
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
