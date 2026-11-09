package pl.odumanager.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String TAG = "OduManager";

    // Strict private IPv4 pattern to protect against SSRF and unauthorized network destinations
    private static final Pattern PRIVATE_IPV4_PATTERN =
            Pattern.compile("^((192\\.168\\.\\d{1,3}\\.\\d{1,3})|(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})|(172\\.(1[6-9]|2\\d|3[0-1])\\.\\d{1,3}\\.\\d{1,3}))$");

    private WebView webView;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile String mSessionCookie = "language=polish";
    private volatile String mDetectedModel = "ODU-IDU 200";
    private volatile String mSavedPassword = "";
    private volatile boolean mIsAuthenticated = false;

    static {
        // Automatically accept and maintain session cookies across HttpURLConnection requests
        CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    }

    private static boolean isValidRouterIp(String ip) {
        if (ip == null || ip.isEmpty() || ip.length() > 15) return false;
        Matcher m = PRIVATE_IPV4_PATTERN.matcher(ip);
        if (!m.matches()) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        for (String part : parts) {
            try {
                int val = Integer.parseInt(part);
                if (val < 0 || val > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private String sanitizeTargetIp(String ip) {
        if (ip != null && isValidRouterIp(ip.trim())) {
            return ip.trim();
        }
        String detected = getDetectedGatewayIp();
        if (isValidRouterIp(detected)) {
            return detected;
        }
        return "192.168.0.1";
    }

    private static String sanitizeBandSelection(String bands) {
        if (bands == null) return "";
        // Only permit alphanumeric characters, commas and spaces (e.g. "B1,B3,B7" or "1,3,7,20,38")
        String sanitized = bands.replaceAll("[^a-zA-Z0-9, ]", "").trim();
        if (sanitized.length() > 64) {
            sanitized = sanitized.substring(0, 64);
        }
        return sanitized;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.parseColor("#EAF2FF"));
            window.setNavigationBarColor(Color.parseColor("#EEF4FF"));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = window.getDecorView().getSystemUiVisibility();
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        // Security hardening: restrict file and content system access from WebView
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setSaveFormData(false);
        settings.setGeolocationEnabled(false);

        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setBackgroundColor(Color.parseColor("#EAF2FF"));
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith("file:///android_asset/")) {
                    return false;
                }
                Log.w(TAG, "Blocked unauthorized navigation attempt to: " + url);
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    String url = request.getUrl().toString();
                    if (url.startsWith("file:///android_asset/")) {
                        return false;
                    }
                    Log.w(TAG, "Blocked unauthorized navigation attempt to: " + url);
                    return true;
                }
                return true;
            }
        });
        webView.addJavascriptInterface(new OduBridge(), "OduBridge");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");

        // React immediately when user switches Wi-Fi or connects/disconnects
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    networkCallback = new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (webView != null) {
                                        webView.evaluateJavascript("window.onNativeResume && window.onNativeResume();", null);
                                    }
                                }
                            });
                        }

                        @Override
                        public void onLost(Network network) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (webView != null) {
                                        webView.evaluateJavascript("window.onNativeResume && window.onNativeResume();", null);
                                    }
                                }
                            });
                        }
                    };
                    cm.registerDefaultNetworkCallback(networkCallback);
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(networkCallback);
                }
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.evaluateJavascript("window.onNativeResume && window.onNativeResume();", null);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("window.handleBackPress ? window.handleBackPress() : false;", new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (!"true".equals(value)) {
                        MainActivity.super.onBackPressed();
                    }
                }
            });
        } else {
            super.onBackPressed();
        }
    }

    private boolean checkWifiTransport() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } else {
                NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null && info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private String getDetectedGatewayIp() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network != null) {
                    LinkProperties lp = cm.getLinkProperties(network);
                    if (lp != null) {
                        for (RouteInfo route : lp.getRoutes()) {
                            if (route.isDefaultRoute() && route.hasGateway()) {
                                InetAddress gw = route.getGateway();
                                if (gw instanceof Inet4Address) {
                                    String ip = gw.getHostAddress();
                                    if (ip != null && !ip.isEmpty() && !"0.0.0.0".equals(ip)) {
                                        return ip;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                DhcpInfo dhcp = wm.getDhcpInfo();
                if (dhcp != null && dhcp.gateway != 0) {
                    int g = dhcp.gateway;
                    String gw = (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "." + ((g >> 16) & 0xFF) + "." + ((g >> 24) & 0xFF);
                    if (!"0.0.0.0".equals(gw)) return gw;
                }
            }
        } catch (Exception ignored) {
        }
        return "192.168.0.1";
    }

    private String fetchHttpString(String urlString, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            String host = url.getHost();
            if (!isValidRouterIp(host)) {
                Log.w(TAG, "Rejected HTTP request to non-private router host: " + host);
                return "";
            }
            conn = (HttpURLConnection) url.openConnection();
            conn.setUseCaches(false);
            conn.setDefaultUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate");
            conn.setRequestProperty("Pragma", "no-cache");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            String cookie = (mSessionCookie != null && !mSessionCookie.isEmpty()) ? mSessionCookie : "language=polish";
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("Referer", "http://" + host + "/idu/home.shtml");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36");

            int code = conn.getResponseCode();
            Log.d(TAG, "fetchHttpString " + urlString + " -> HTTP " + code);

            if (code == 302 || code == 301) {
                String loc = conn.getHeaderField("Location");
                Log.d(TAG, "fetchHttpString 302 redirect to: " + loc);
                if (loc != null && (loc.contains("netlock") || loc.contains("simlock"))) {
                    return "<!-- REDIRECT: " + loc + " -->\n<title>Blokada SIM Lock</title><div>Blokada SIM Lock (karta niedozwolona)</div>";
                }
                if (loc != null && !loc.contains("login.shtml") && !loc.equals(urlString)) {
                    String nextUrl = loc.startsWith("http") ? loc : ("http://" + host + (loc.startsWith("/") ? "" : "/") + loc);
                    return fetchHttpString(nextUrl, timeoutMs);
                }
            }

            if (code >= 200 && code < 400) {
                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                int total = 0;
                while ((line = reader.readLine()) != null && total < 131072) {
                    sb.append(line).append('\n');
                    total += line.length();
                }
                reader.close();
                return sb.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchHttpString error on " + urlString + ": " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return "";
    }

    public class OduBridge {
        @JavascriptInterface
        public boolean isWifiConnected() {
            return checkWifiTransport();
        }

        @JavascriptInterface
        public void openWifiSettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Nie można otworzyć ustawień Wi-Fi", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public void showToast(final String msg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void checkOduConnection(final String gatewayUrl) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final boolean wifiOn = checkWifiTransport();
                    String gwCandidate = sanitizeTargetIp(getDetectedGatewayIp());
                    boolean oduDetected = false;
                    long realMeasuredPing = -1;

                    String setModel = "ODU-IDU 200";
                    String iduModel = "IDU-200";
                    String oduModel = "ODU-200";
                    String catSpec = "LTE Cat. 4 • 150 Mb/s";
                    String bandsSpec = "FDD B3 / B7 / B20";
                    String coaxSpec = "PoCoax • Unicable LNB";

                    if (wifiOn) {
                        String[] candidates = gwCandidate.equals("192.168.0.1")
                                ? new String[]{"192.168.0.1"}
                                : new String[]{gwCandidate, "192.168.0.1"};

                        for (String ip : candidates) {
                            if (!isValidRouterIp(ip)) continue;
                            long t0 = System.currentTimeMillis();
                            String rootHtml = fetchHttpString("http://" + ip + "/", 2000);
                            long elapsed = System.currentTimeMillis() - t0;

                            if (!rootHtml.isEmpty()) {
                                boolean looksLikeOdu = rootHtml.contains("formLogin")
                                        || rootHtml.contains("/cgi-bin/adm.cgi")
                                        || rootHtml.contains("/idu/home")
                                        || rootHtml.contains("b28n.js")
                                        || rootHtml.contains("ODU")
                                        || rootHtml.contains("IDU");

                                String adminXml = fetchHttpString("http://" + ip + "/lang/en/admin.xml", 2000);
                                if (!adminXml.isEmpty()) {
                                    looksLikeOdu = true;
                                }

                                if (looksLikeOdu) {
                                    oduDetected = true;
                                    gwCandidate = ip;
                                    realMeasuredPing = Math.max(1, elapsed);

                                    // 1. ODU-IDU 200 signature: U270, IDU-200, ODU-200, MT7620 Cat.4
                                    boolean is200 = adminXml.contains("U270")
                                            || adminXml.contains("IDU-200")
                                            || adminXml.contains("ODU-200")
                                            || rootHtml.contains("IDU-200");

                                    // 2. ODU-IDU 100 signature: U100, IDU-100, ODU-100, RT3352 Cat.3
                                    boolean is100 = !is200 && (adminXml.contains("U100")
                                            || adminXml.contains("IDU-100")
                                            || adminXml.contains("ODU-100")
                                            || rootHtml.contains("IDU-100"));

                                    // 3. ODU-IDU 300 signature: U300, IDU-300, ODU-300, MT7621 Cat.6
                                    boolean is300 = !is200 && !is100 && (adminXml.contains("U300")
                                            || adminXml.contains("IDU-300")
                                            || adminXml.contains("ODU-300")
                                            || rootHtml.contains("IDU-300"));

                                    if (!is200 && !is100 && !is300) {
                                        // Default fallback based on platform keywords
                                        if (adminXml.contains("U270") || rootHtml.contains("200")) {
                                            is200 = true;
                                        } else {
                                            is300 = true;
                                        }
                                    }

                                    Log.d(TAG, "Probing IP: " + ip + " rootHtml len: " + rootHtml.length() + " adminXml len: " + adminXml.length());
                                    Log.d(TAG, "Debug search - mesh_tplg: " + adminXml.indexOf("mesh_tplg") + ", U270: " + adminXml.indexOf("U270") + ", mesh: " + adminXml.indexOf("mesh"));
                                    Log.d(TAG, "Fingerprint check: is300=" + is300 + ", is200=" + is200 + ", is100=" + is100);

                                    if (is300) {
                                        setModel = "ODU-IDU 300";
                                        iduModel = "IDU-300";
                                        oduModel = "ODU-300";
                                        catSpec = "LTE Cat. 6 • 300 Mb/s (2CA)";
                                        bandsSpec = "B1 / B3 / B7 / B8 / B20 + B38 TDD";
                                        coaxSpec = "PoCoax • Unicable LNB";
                                    } else if (is200) {
                                        setModel = "ODU-IDU 200";
                                        iduModel = "IDU-200";
                                        oduModel = "ODU-200";
                                        catSpec = "LTE Cat. 4 • 150 Mb/s";
                                        bandsSpec = "B3 / B7 / B8 / B20 + B38 TDD";
                                        coaxSpec = "PoCoax • Unicable LNB";
                                    } else if (is100) {
                                        setModel = "ODU-IDU 100";
                                        iduModel = "IDU-100";
                                        oduModel = "ODU-100";
                                        catSpec = "LTE Cat. 3 • 30 Mb/s";
                                        bandsSpec = "FDD B3 (1800 MHz)";
                                        coaxSpec = "PoCoax • Zasilanie ODU";
                                    } else {
                                        setModel = "ODU-IDU 300";
                                        iduModel = "IDU-300";
                                        oduModel = "ODU-300";
                                        catSpec = "LTE Cat. 6 • 300 Mb/s (2CA)";
                                        bandsSpec = "B1 / B3 / B7 / B8 / B20 + B38 TDD";
                                        coaxSpec = "PoCoax • Unicable LNB";
                                    }
                                    Log.d(TAG, "Selected model: " + setModel + " (Ping: " + realMeasuredPing + " ms)");
                                    mDetectedModel = setModel;
                                    break;
                                }
                            }
                        }
                    }

                    final boolean finalOdu = oduDetected;
                    final String finalGw = gwCandidate;
                    final long finalPing = realMeasuredPing;
                    final String fSetModel = setModel;
                    final String fIduModel = iduModel;
                    final String fOduModel = oduModel;
                    final String fCatSpec = catSpec;
                    final String fBandsSpec = bandsSpec;
                    final String fCoaxSpec = coaxSpec;

                    JSONArray initAlerts = new JSONArray();
                    final String initMsgsJson = initAlerts.toString();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String js = String.format(
                                "window.onConnectionStatus && window.onConnectionStatus(%b, %b, %s, %d, %s, %s, %s, %s, %s, %s, %s);",
                                wifiOn,
                                finalOdu,
                                JSONObject.quote(finalGw),
                                finalPing,
                                JSONObject.quote(fSetModel),
                                JSONObject.quote(fIduModel),
                                JSONObject.quote(fOduModel),
                                JSONObject.quote(fCatSpec),
                                JSONObject.quote(fBandsSpec),
                                JSONObject.quote(fCoaxSpec),
                                initMsgsJson
                            );
                            webView.evaluateJavascript(js, null);
                        }
                    });
                }
            }).start();
        }

        private boolean tryFormLogin(String targetIp, String endpoint, String username, String password, StringBuilder outAdmBody, StringBuilder outLocation, long[] outPing) {
            HttpURLConnection conn = null;
            try {
                long t0 = System.currentTimeMillis();
                URL url = new URL("http://" + targetIp + endpoint);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3500);
                conn.setReadTimeout(3500);
                conn.setRequestMethod("POST");
                conn.setInstanceFollowRedirects(false);
                conn.setDoOutput(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36");
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                conn.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Origin", "http://" + targetIp);
                conn.setRequestProperty("Referer", "http://" + targetIp + "/login.asp");
                conn.setRequestProperty("Cookie", "language=polish");

                String body;
                if (endpoint.contains("formLogin")) {
                    body = "loginName=" + URLEncoder.encode(username, "UTF-8")
                            + "&loginPassword=" + URLEncoder.encode(password, "UTF-8");
                } else {
                    body = "page=Login&loginName=" + URLEncoder.encode(username, "UTF-8")
                            + "&loginPassword=" + URLEncoder.encode(password, "UTF-8");
                }

                byte[] postBytes = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(postBytes.length);
                OutputStream os = conn.getOutputStream();
                os.write(postBytes);
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                if (outPing != null && outPing.length > 0) {
                    outPing[0] = Math.max(1, System.currentTimeMillis() - t0);
                }
                String location = conn.getHeaderField("Location");
                if (location == null) location = "";
                if (outLocation != null) {
                    outLocation.setLength(0);
                    outLocation.append(location);
                }

                String admBody = "";
                try {
                    InputStream is = (responseCode >= 200 && responseCode < 400) ? conn.getInputStream() : conn.getErrorStream();
                    if (is != null) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                        StringBuilder b = new StringBuilder();
                        String l;
                        while ((l = r.readLine()) != null) b.append(l).append('\n');
                        r.close();
                        admBody = b.toString();
                    }
                } catch (Exception ignored) {}
                if (outAdmBody != null) {
                    outAdmBody.setLength(0);
                    outAdmBody.append(admBody);
                }

                Log.d(TAG, "tryFormLogin " + endpoint + " -> code=" + responseCode + ", loc=" + location + ", admBody len=" + admBody.length() + ": " + admBody.replace("\n", " "));

                java.util.Map<String, java.util.List<String>> headers = conn.getHeaderFields();
                if (headers != null) {
                    java.util.List<String> cookies = headers.get("Set-Cookie");
                    if (cookies == null) cookies = headers.get("set-cookie");
                    if (cookies != null && !cookies.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (String c : cookies) {
                            String cookieVal = c.split(";")[0].trim();
                            if (sb.length() > 0) sb.append("; ");
                            sb.append(cookieVal);
                        }
                        if (!sb.toString().contains("language=")) {
                            sb.append("; language=polish");
                        }
                        mSessionCookie = sb.toString();
                        Log.d(TAG, "session cookie saved: " + mSessionCookie);
                    }
                }

                boolean isSuccess = location.contains("home.asp") || location.contains("home.shtml")
                        || admBody.contains("home.asp") || admBody.contains("home.shtml");

                boolean isExplicitFail = location.contains("login.asp") || location.contains("login.shtml")
                        || location.contains("id=401") || admBody.contains("top.location.href = \"/login.asp\"")
                        || admBody.contains("top.location.href='/login.asp'") || admBody.contains("id=401");

                if (isSuccess) return true;
                if (isExplicitFail) return false;

                if (responseCode == 200 && admBody.length() > 50 && !admBody.contains("login")) {
                    return true;
                }
                return false;
            } catch (Exception e) {
                Log.w(TAG, "tryFormLogin error on " + endpoint + ": " + e.getMessage());
                return false;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        private boolean loginInternal(String targetIp, String safeUsername, String safePassword) {
            StringBuilder admBody = new StringBuilder();
            StringBuilder location = new StringBuilder();
            // 1. Try GoAhead /goform/formLogin (IDU-200 standard)
            boolean ok = tryFormLogin(targetIp, "/goform/formLogin", safeUsername, safePassword, admBody, location, null);
            if (!ok && (admBody.toString().contains("404") || admBody.length() == 0)) {
                // 2. Fallback to /cgi-bin/adm.cgi
                ok = tryFormLogin(targetIp, "/cgi-bin/adm.cgi", safeUsername, safePassword, admBody, location, null);
            }
            return ok;
        }

        @JavascriptInterface
        public void loginToRouter(final String gatewayIp, final String username, final String password) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean success = false;
                    String message = "";
                    long realPing = -1;
                    String jsonTelemetry = "{}";

                    try {
                        String targetIp = sanitizeTargetIp(gatewayIp);
                        String safeUsername = (username != null) ? username.trim() : "admin";
                        String safePassword = (password != null) ? password.trim() : "";
                        mSavedPassword = safePassword;

                        StringBuilder admBody = new StringBuilder();
                        StringBuilder location = new StringBuilder();
                        long[] pingArr = new long[]{ -1 };

                        // 1. Try GoAhead /goform/formLogin (ODU-IDU 200 native)
                        boolean ok = tryFormLogin(targetIp, "/goform/formLogin", safeUsername, safePassword, admBody, location, pingArr);
                        if (!ok && (admBody.toString().contains("404") || admBody.length() == 0)) {
                            // 2. Fallback to /cgi-bin/adm.cgi
                            ok = tryFormLogin(targetIp, "/cgi-bin/adm.cgi", safeUsername, safePassword, admBody, location, pingArr);
                        }

                        realPing = pingArr[0];
                        String admBodyStr = admBody.toString();
                        String locStr = location.toString();
                        boolean is401_2 = locStr.contains("401.2") || admBodyStr.contains("401.2");

                        if (ok) {
                            success = true;
                            mSavedPassword = safePassword;
                            mIsAuthenticated = true;
                            message = "Zalogowano pomyślnie do " + targetIp;

                            // Fetch genuine telemetry pages from IDU-200
                            String webbodyHtml = fetchHttpString("http://" + targetIp + "/idu/webbody.asp", 2500);
                            String deviceHtml = fetchHttpString("http://" + targetIp + "/idu/bodyinfo_device.asp", 2500);
                            String lteInfoHtml = fetchHttpString("http://" + targetIp + "/idu/bodyinfo_lte.asp", 2500);
                            String homeAspHtml = fetchHttpString("http://" + targetIp + "/idu/home.asp", 2000);
                            String lteStatus = fetchHttpString("http://" + targetIp + "/goform/showlteStatus", 2000);

                            // Alternative firmware fallbacks
                            String homeShtml = fetchHttpString("http://" + targetIp + "/idu/home.shtml", 1500);
                            String lteShtml = fetchHttpString("http://" + targetIp + "/idu/lte.shtml", 1500);

                            String combined = webbodyHtml + "\n" + deviceHtml + "\n" + lteInfoHtml + "\n" + homeAspHtml + "\n" + lteStatus + "\n" + homeShtml + "\n" + lteShtml;
                            jsonTelemetry = parseRouterTelemetry(combined, realPing, targetIp, mDetectedModel);
                        } else {
                            success = false;
                            mSavedPassword = "";
                            mIsAuthenticated = false;
                            if (is401_2) {
                                message = "Router tymczasowo zablokował logowanie (zbyt wiele nieudanych prób). Odczekaj 2 minuty i spróbuj ponownie.";
                            } else {
                                message = "Nieprawidłowe hasło routera. Wpisz poprawne hasło administratora.";
                            }
                        }
                    } catch (Exception e) {
                        success = false;
                        message = "Błąd połączenia: " + e.getMessage();
                    }

                    final boolean finalSuccess = success;
                    final String finalMsg = message;
                    final String finalJson = jsonTelemetry;

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String js = String.format(
                                "window.onLoginResult && window.onLoginResult(%b, %s, %s);",
                                finalSuccess,
                                JSONObject.quote(finalMsg),
                                finalJson
                            );
                            webView.evaluateJavascript(js, null);
                        }
                    });
                }
            }).start();
        }

        @JavascriptInterface
        public void refreshRealTelemetry(final String gatewayIp) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String targetIp = sanitizeTargetIp(gatewayIp);
                    long t0 = System.currentTimeMillis();
                    String webbodyHtml = fetchHttpString("http://" + targetIp + "/idu/webbody.asp", 2500);
                    long realPing = Math.max(1, System.currentTimeMillis() - t0);

                    boolean isUnauth = webbodyHtml.isEmpty() || webbodyHtml.contains("/login.asp") || webbodyHtml.contains("top.location=\"/login.shtml\"") || webbodyHtml.length() < 300;
                    if (isUnauth && mSavedPassword != null && !mSavedPassword.isEmpty()) {
                        Log.d(TAG, "refreshRealTelemetry: attempting relogin with saved password...");
                        boolean ok = loginInternal(targetIp, "admin", mSavedPassword);
                        if (ok) {
                            webbodyHtml = fetchHttpString("http://" + targetIp + "/idu/webbody.asp", 2500);
                        } else {
                            mSavedPassword = "";
                        }
                    }

                    String deviceHtml = fetchHttpString("http://" + targetIp + "/idu/bodyinfo_device.asp", 2000);
                    String lteInfoHtml = fetchHttpString("http://" + targetIp + "/idu/bodyinfo_lte.asp", 2000);
                    String homeAspHtml = fetchHttpString("http://" + targetIp + "/idu/home.asp", 2000);
                    String lteStatus = fetchHttpString("http://" + targetIp + "/goform/showlteStatus", 1500);

                    String homeShtml = fetchHttpString("http://" + targetIp + "/idu/home.shtml", 1500);
                    String lteShtml = fetchHttpString("http://" + targetIp + "/idu/lte.shtml", 1500);

                    String combined = webbodyHtml + "\n" + deviceHtml + "\n" + lteInfoHtml + "\n" + homeAspHtml + "\n" + lteStatus + "\n" + homeShtml + "\n" + lteShtml;
                    Log.d(TAG, "Telemetry HTML lens - webbody: " + webbodyHtml.length() + ", device: " + deviceHtml.length() + ", lteInfo: " + lteInfoHtml.length());

                    final String json = parseRouterTelemetry(combined, realPing, targetIp, mDetectedModel);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            webView.evaluateJavascript("window.onTelemetryUpdate && window.onTelemetryUpdate(" + json + ");", null);
                        }
                    });
                }
            }).start();
        }

        private String parseRouterTelemetry(String html, long realPing, String targetIp, String setModel) {
            String rsrp = extractParam(html, new String[]{"rsrp", "lte_rsrp", "lteRsrp", "RSRP"}, new String[]{"RSRP", "Moc sygnału RSRP"});
            String rsrq = extractParam(html, new String[]{"rsrq", "lte_rsrq", "lteRsrq", "RSRQ"}, new String[]{"RSRQ", "Jakość sygnału RSRQ"});
            String sinr = extractParam(html, new String[]{"sinr", "lte_sinr", "lteSinr", "SINR", "snr", "SNR"}, new String[]{"SINR", "SNR", "Stosunek sygnału do szumu"});
            String rssi = extractParam(html, new String[]{"rssi", "lte_rssi", "lteRssi", "RSSI"}, new String[]{"RSSI"});
            String band = extractParam(html, new String[]{"band", "lte_band", "lteBand", "BAND", "current_band"}, new String[]{"Pasmo", "Band", "Częstotliwość"});
            String cellId = extractParam(html, new String[]{"cellid", "cell_id", "cellId", "enodeb", "eNB", "eNodeB"}, new String[]{"Cell ID", "eNodeB", "Identyfikator stacji"});

            int signalLevel = extractSignalLevel(html, rsrp);
            boolean hasSignal = signalLevel > 0;

            // Extract from JavaScript array 'a' if present (standard IDU-200/ODU-IDU GoAhead)
            Pattern pArrayA = Pattern.compile("a\\s*=\\s*\\[([^\\]]+)\\]");
            Matcher mArrayA = pArrayA.matcher(html);
            if (mArrayA.find()) {
                String rawElements = mArrayA.group(1);
                String[] items = rawElements.split(",");
                for (int i = 0; i < items.length; i++) {
                    items[i] = items[i].trim().replaceAll("^[\"']|[\"']$", "").trim();
                }
                if (items.length > 11) {
                    String aUsim = (items.length > 2) ? items[2] : "";
                    String aSignal = (items.length > 11) ? items[11] : "";
                    String aRsrp = (items.length > 10) ? items[10] : "";
                    String aRsrq = (items.length > 9) ? items[9] : "";
                    String aSinr = (items.length > 21) ? items[21] : "";
                    String aRssi = (items.length > 24) ? items[24] : "";

                    if (!aRsrp.contains("N/A") && !aRsrp.isEmpty() && !aRsrp.equalsIgnoreCase("Unknown")) {
                        rsrp = aRsrp;
                    }
                    if (!aRsrq.contains("N/A") && !aRsrq.isEmpty() && !aRsrq.equalsIgnoreCase("Unknown")) {
                        rsrq = aRsrq;
                    }
                    if (!aSinr.contains("N/A") && !aSinr.isEmpty() && !aSinr.equalsIgnoreCase("Unknown")) {
                        sinr = aSinr;
                    }
                    if (!aRssi.contains("N/A") && !aRssi.isEmpty() && !aRssi.equalsIgnoreCase("Unknown")) {
                        rssi = aRssi;
                    }

                    if (aSignal.equalsIgnoreCase("No Signal") || aUsim.contains("SIMLOCK") || html.contains("NETLOCK Locked")) {
                        hasSignal = false;
                        signalLevel = 0;
                    }
                }
            }

            boolean isLtePlus = hasSignal && (html.contains("2CA") || html.contains("LTE+") || html.contains("LTE-A") || html.contains("CA"));

            // Real data rule: If no cellular signal from SIM/antenna, show "---" across all RF metrics
            if (!hasSignal) {
                rsrp = "---";
                rsrq = "---";
                sinr = "---";
                rssi = "---";
                band = "---";
                cellId = "---";
            }

            JSONArray messages = buildRouterMessages(html, hasSignal, isLtePlus, band, cellId, setModel, realPing, targetIp);
            Log.d(TAG, "parseRouterTelemetry result -> hasSignal=" + hasSignal + ", msgs=" + messages.toString());

            try {
                JSONObject json = new JSONObject();
                json.put("realPingMs", realPing);
                json.put("signalLevel", signalLevel);
                json.put("hasSignal", hasSignal);
                json.put("isLtePlus", isLtePlus);
                json.put("rsrp", rsrp);
                json.put("rsrq", rsrq);
                json.put("sinr", sinr);
                json.put("rssi", rssi);
                json.put("band", band);
                json.put("cellId", cellId);
                json.put("messages", messages);
                return json.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        private String extractRawRouterAlert(String html) {
            if (html == null || html.isEmpty()) return null;
            try {
                // 1. Look for error / result table cells and divs used in IDU-200 / IDU-300 firmware
                Pattern p1 = Pattern.compile("(?i)<(?:td|div|span|p)[^>]*id=[\"'](?:errresult|result|alert_msg|err_msg|error_msg|status_msg)[\"'][^>]*>([^<]+)</(?:td|div|span|p)>");
                Matcher m1 = p1.matcher(html);
                while (m1.find()) {
                    String txt = m1.group(1).trim().replace("&nbsp;", " ").trim();
                    if (!txt.isEmpty() && !txt.equals("-") && !txt.equalsIgnoreCase("null") && !txt.equalsIgnoreCase("undefined") && !txt.contains("XMLHTTP") && !txt.contains("Giving up")) {
                        return txt;
                    }
                }
                // 2. Look for JS alert or error functions
                Pattern p2 = Pattern.compile("(?i)(?:alert|showError|showMsg|displayError)\\s*\\(\\s*[\"']([^\"']{4,120})[\"']\\s*\\)");
                Matcher m2 = p2.matcher(html);
                if (m2.find()) {
                    String txt = m2.group(1).trim();
                    if (!txt.isEmpty() && !txt.contains("<") && !txt.contains(">") && !txt.contains("XMLHTTP") && !txt.contains("Giving up")) {
                        return txt;
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        private JSONArray buildRouterMessages(String html, boolean hasSignal, boolean isLtePlus, String band, String cellId, String setModel, long realPing, String targetIp) {
            JSONArray arr = new JSONArray();
            String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

            try {
                // Intercept raw router error/alert message if present in HTML
                String rawAlert = extractRawRouterAlert(html);
                if (rawAlert != null && !rawAlert.isEmpty()) {
                    JSONObject msgAlert = new JSONObject();
                    msgAlert.put("id", "router_raw_alert");
                    msgAlert.put("category", "KOMUNIKAT Z ROUTERA");
                    msgAlert.put("time", timeStr);
                    msgAlert.put("level", "danger");
                    msgAlert.put("icon", "⚠️");
                    msgAlert.put("title", "Komunikat z panelu routera");
                    msgAlert.put("text", rawAlert);
                    arr.put(msgAlert);
                }

                // 1. ODU-IDU connection fault
                boolean oduDisconnected = html.contains("Brak połączenia pomiędzy ODU oraz IDU")
                        || html.contains("Brak połączenia z ODU")
                        || html.contains("ODU Disconnected")
                        || html.contains("ODU connection fail")
                        || html.contains("home content disconnected display")
                        || html.contains("odu config alert2");

                if (oduDisconnected) {
                    JSONObject msgOdu = new JSONObject();
                    msgOdu.put("id", "odu_link_fault");
                    msgOdu.put("category", "AWARIA ODU");
                    msgOdu.put("time", timeStr);
                    msgOdu.put("level", "danger");
                    msgOdu.put("icon", "⚠️");
                    msgOdu.put("title", "Brak połączenia pomiędzy ODU a IDU");
                    msgOdu.put("text", "Router IDU zgłasza brak połączenia z anteną zewnętrzną ODU. Sprawdź kabel koncentryczny anteny i zasilanie PoCoax.");
                    arr.put(msgOdu);
                }

                // 2. Blokada SIM Lock / NETLOCK / Operator Lock (np. karta Orange w routerze Plus)
                boolean isSimLock = html.contains("Blokada SIM Lock")
                        || html.contains("karta niedozwolona")
                        || html.contains("language USIM Netlock")
                        || html.contains("language USIM Not Ready")
                        || html.contains("USIM Not Ready")
                        || html.contains("USIM Netlock")
                        || html.contains("USIM SIMLOCK")
                        || html.contains("NETLOCK Locked")
                        || html.contains("Operator Lock")
                        || html.contains("NETLOCK")
                        || html.contains("netlock")
                        || html.contains("simlock")
                        || html.contains("Unlock NETLOCK")
                        || html.contains("W celu zdjęcia blokady SIM Lock")
                        || html.contains("W celu zdj")
                        || html.contains("Code to disable Operator Lock")
                        || html.contains("disable the Operator Lock")
                        || html.contains("card not permitted")
                        || html.contains("Device is locked");

                if (isSimLock) {
                    JSONObject msgLock = new JSONObject();
                    msgLock.put("id", "sim_lock_operator");
                    msgLock.put("category", "BLOKADA OPERATORA");
                    msgLock.put("time", timeStr);
                    msgLock.put("level", "danger");
                    msgLock.put("icon", "🔒");
                    msgLock.put("title", "Blokada SIM Lock: Karta Orange zablokowana");
                    msgLock.put("text", "Włożona karta Orange jest zablokowana przez blokadę operatora Plus/Cyfrowy Polsat (USIM SIMLOCK • NETLOCK Locked). Router wymaga kodu odblokowującego (Code to disable Operator Lock).");
                    arr.put(msgLock);
                }

                // 3. Karta USIM nieprawidłowa
                boolean isSimInvalid = !isSimLock && (html.contains("karta USIM jest nieprawidłowa")
                        || html.contains("karta SIM jest nieprawidłowa")
                        || html.contains("USIM Card invalid")
                        || html.contains("invalid sim card")
                        || html.contains("disconnected invalid sim card")
                        || html.contains("Nieprawidłowa karta"));

                if (isSimInvalid) {
                    JSONObject msgSimInv = new JSONObject();
                    msgSimInv.put("id", "sim_invalid");
                    msgSimInv.put("category", "BŁĄD KARTY SIM");
                    msgSimInv.put("time", timeStr);
                    msgSimInv.put("level", "danger");
                    msgSimInv.put("icon", "🚫");
                    msgSimInv.put("title", "Karta SIM nieprawidłowa lub nieobsługiwana");
                    msgSimInv.put("text", "Router zgłasza: Karta USIM w modemie ODU jest nieprawidłowa lub nie pochodzi od autoryzowanego operatora sieci.");
                    arr.put(msgSimInv);
                }

                // 4. SIM / USIM faults (PIN / PUK / Missing)
                boolean pukLock = html.contains("PUK lock") || html.contains("PUK jest Zamknięty") || html.contains("Wymagany PUK") || html.contains("PUK jest zablokowany");
                boolean pinLock = html.contains("PIN lock") || html.contains("PIN jest Zamknięty") || html.contains("Wymagany PIN") || html.contains("PIN jest zablokowany");
                boolean simMissing = html.contains("No SIM inserted")
                        || html.contains("Brak karty SIM")
                        || html.contains("SIM card not inserted")
                        || html.contains("Nie włożono karty")
                        || html.contains("SIM not inserted");

                if (!isSimLock && !isSimInvalid) {
                    if (pukLock) {
                        JSONObject msgSim = new JSONObject();
                        msgSim.put("id", "sim_puk_lock");
                        msgSim.put("category", "BLOKADA SIM");
                        msgSim.put("time", timeStr);
                        msgSim.put("level", "danger");
                        msgSim.put("icon", "🚫");
                        msgSim.put("title", "Karta SIM zablokowana (kod PUK)");
                        msgSim.put("text", "Karta SIM w modemie zewnętrznym ODU została zablokowana. Wymagane odblokowanie kodem PUK od operatora.");
                        arr.put(msgSim);
                    } else if (pinLock) {
                        JSONObject msgSim = new JSONObject();
                        msgSim.put("id", "sim_pin_lock");
                        msgSim.put("category", "BLOKADA SIM");
                        msgSim.put("time", timeStr);
                        msgSim.put("level", "warning");
                        msgSim.put("icon", "🔐");
                        msgSim.put("title", "Wymagany kod PIN karty SIM");
                        msgSim.put("text", "Karta SIM oczekuje na autoryzację kodem PIN w panelu routera.");
                        arr.put(msgSim);
                    } else if (simMissing) {
                        JSONObject msgSim = new JSONObject();
                        msgSim.put("id", "sim_missing_fault");
                        msgSim.put("category", "BŁĄD KARTY SIM");
                        msgSim.put("time", timeStr);
                        msgSim.put("level", "danger");
                        msgSim.put("icon", "✕");
                        msgSim.put("title", "Brak karty SIM w modemie ODU");
                        msgSim.put("text", "Router zgłasza brak karty USIM w slocie modemu ODU.");
                        arr.put(msgSim);
                    }
                }

                // 5. Unauthenticated check - router requires login
                boolean isUnauthenticated = html.replace("\n", "").trim().isEmpty()
                        || html.contains("/login.asp")
                        || html.contains("top.location=\"/login.shtml\"")
                        || html.contains("id=401")
                        || (html.length() < 500 && !html.contains("rsrp") && !html.contains("netlock") && !html.contains("simlock") && !html.contains("Blokada SIM Lock"));

                if (isUnauthenticated) {
                    JSONObject msgAuth = new JSONObject();
                    msgAuth.put("id", "auth_required");
                    msgAuth.put("category", "AUTORYZACJA");
                    msgAuth.put("time", timeStr);
                    msgAuth.put("level", "warning");
                    msgAuth.put("icon", "🔐");
                    msgAuth.put("title", "Wymagane logowanie do routera");
                    msgAuth.put("text", "Aplikacja oczekuje na autoryzację w panelu routera. Kliknij w ikonę urządzenia i podaj hasło administratora.");
                    arr.put(msgAuth);
                }

                // 6. Cellular signal fault - only when truly authenticated and no other fault
                if (!hasSignal && !oduDisconnected && !isSimLock && !isSimInvalid && !simMissing && !pinLock && !pukLock && !isUnauthenticated) {
                    JSONObject msgNet = new JSONObject();
                    msgNet.put("id", "no_cellular_signal");
                    msgNet.put("category", "BRAK SYGNAŁU");
                    msgNet.put("time", timeStr);
                    msgNet.put("level", "danger");
                    msgNet.put("icon", "📵");
                    msgNet.put("title", "Brak zasięgu sieci komórkowej");
                    msgNet.put("text", "Router nie odbiera sygnału ze stacji bazowej operatora. Sprawdź ustawienie anteny zewnętrznej.");
                    arr.put(msgNet);
                }

                // 4. SMS storage full fault
                if (html.contains("message is full") || html.contains("Usuń wiadomości aby zwolnić pamięć SMS")) {
                    JSONObject msgSms = new JSONObject();
                    msgSms.put("id", "sms_full_alert");
                    msgSms.put("category", "PAMIĘĆ SMS");
                    msgSms.put("time", timeStr);
                    msgSms.put("level", "warning");
                    msgSms.put("icon", "💬");
                    msgSms.put("title", "Pamięć SMS jest przepełniona");
                    msgSms.put("text", "Pamięć skrzynki SMS na karcie SIM jest pełna. Nowe wiadomości mogą nie docierać.");
                    arr.put(msgSms);
                }

            } catch (Exception ignored) {
            }

            return arr;
        }

        private boolean isValidValue(String val) {
            if (val == null) return false;
            String v = val.trim();
            if (v.isEmpty() || v.equals("-") || v.equals("--") || v.equals("---")
                    || v.equalsIgnoreCase("null") || v.equalsIgnoreCase("undefined")
                    || v.equalsIgnoreCase("n/a") || v.equalsIgnoreCase("brak")
                    || v.equalsIgnoreCase("none")) {
                return false;
            }
            return true;
        }

        private String extractParam(String text, String[] keys, String[] labels) {
            if (text == null || text.isEmpty()) return "---";

            for (String key : keys) {
                Pattern p1 = Pattern.compile("(?i)(?:var\\s+)?[\"']?" + Pattern.quote(key) + "[\"']?\\s*[:=]\\s*['\"]([^'\"]+)['\"]");
                Matcher m1 = p1.matcher(text);
                if (m1.find()) {
                    String val = m1.group(1).trim();
                    if (isValidValue(val)) return val;
                }
            }

            for (String label : labels) {
                Pattern p2 = Pattern.compile("(?i)(?:" + Pattern.quote(label) + ")[\\s:=]*</t[dh]?>?\\s*<t[dh][^>]*>([^<]+)</t[dh]>");
                Matcher m2 = p2.matcher(text);
                if (m2.find()) {
                    String val = m2.group(1).trim();
                    if (isValidValue(val)) return val;
                }

                Pattern p3 = Pattern.compile("(?i)(?:" + Pattern.quote(label) + ")[\\s:=]+(?:<[^>]+>)?([^<\\r\\n]+)");
                Matcher m3 = p3.matcher(text);
                if (m3.find()) {
                    String val = m3.group(1).trim();
                    if (isValidValue(val)) return val;
                }
            }

            return "---";
        }

        private int extractSignalLevel(String text, String rsrpStr) {
            if (text == null || text.isEmpty()) return 0;

            if (text.contains("No SIM inserted") || text.contains("Brak karty SIM")
                    || text.contains("SIM card not inserted") || text.contains("Nie włożono karty")
                    || text.contains("Rozłączono") || text.contains("Disconnected")
                    || text.contains("Brak usługi") || text.contains("No Service")
                    || text.contains("Brak zasięgu") || text.contains("No signal")) {
                return 0;
            }

            Pattern p = Pattern.compile("(?i)(?:sigLevel|signal_level|signal|lteSigLevel|sig_level|bars)\\s*[:=]\\s*['\"]?([0-5])['\"]?");
            Matcher m = p.matcher(text);
            if (m.find()) {
                try {
                    int lvl = Integer.parseInt(m.group(1));
                    return Math.max(0, Math.min(5, lvl));
                } catch (Exception ignored) {
                }
            }

            if (rsrpStr != null && !rsrpStr.equals("---")) {
                try {
                    String num = rsrpStr.replaceAll("[^-\\d]", "");
                    if (!num.isEmpty() && !num.equals("-")) {
                        int r = Integer.parseInt(num);
                        if (r <= -140 || r >= 0) return 0;
                        if (r >= -80) return 5;
                        if (r >= -90) return 4;
                        if (r >= -100) return 3;
                        if (r >= -110) return 2;
                        return 1;
                    }
                } catch (Exception ignored) {
                }
            }

            // Real data rule: If no cellular signal is confirmed, return 0 (no signal), never a fake 4 bars!
            return 0;
        }

        @JavascriptInterface
        public void applyBandLock(final String gatewayIp, final String bandSelection) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String targetIp = sanitizeTargetIp(gatewayIp);
                        String safeBands = sanitizeBandSelection(bandSelection);
                        URL url = new URL("http://" + targetIp + "/cgi-bin/adm.cgi");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(3000);
                        conn.setReadTimeout(3000);
                        conn.setRequestMethod("POST");
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        conn.setRequestProperty("Referer", "http://" + targetIp + "/idu/lte.shtml");

                        String body = "page=lteband&band=" + URLEncoder.encode(safeBands, "UTF-8");
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        OutputStream os = conn.getOutputStream();
                        os.write(bytes);
                        os.flush();
                        os.close();
                        conn.getResponseCode();
                        conn.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            }).start();
        }
    }
}
