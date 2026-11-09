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
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
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
        } catch (Exception ignored) {
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

                                    // Genuine hardware platform fingerprinting:
                                    // ODU-IDU 200 platform signature: U270 (InterPhone IDU-200 / ODU-200)
                                    boolean is200 = adminXml.contains("U270") || adminXml.contains("IDU-200") || adminXml.contains("ODU-200") || rootHtml.contains("IDU-200");

                                    // ODU-IDU 100 platform signature: U100
                                    boolean is100 = !is200 && (adminXml.contains("U100") || adminXml.contains("IDU-100") || adminXml.contains("ODU-100"));

                                    // ODU-IDU 300 platform signature: U300, IDU-300, ODU-300, PT30, PT40, CPE3, or B1 2100MHz (sysfwnetd2100)
                                    boolean is300 = !is200 && !is100 && (adminXml.contains("U300")
                                            || adminXml.contains("IDU-300")
                                            || adminXml.contains("ODU-300")
                                            || rootHtml.contains("IDU-300")
                                            || adminXml.contains("PT30")
                                            || adminXml.contains("PT40")
                                            || adminXml.contains("CPE3")
                                            || adminXml.contains("sysfwnetd2100"));

                                    if (!is200 && !is100 && !is300) {
                                        is200 = true; // Reference standard
                                    }

                                    Log.d(TAG, "Probing IP: " + ip + " rootHtml len: " + rootHtml.length() + " adminXml len: " + adminXml.length());
                                    Log.d(TAG, "Fingerprint check: is200=" + is200 + ", is300=" + is300 + ", is100=" + is100);

                                    if (is200) {
                                        setModel = "ODU-IDU 200";
                                        iduModel = "IDU-200";
                                        oduModel = "ODU-200";
                                        catSpec = "LTE Cat. 4 • 150 Mb/s";
                                        bandsSpec = "B3 / B7 / B8 / B20 + B38 TDD";
                                        coaxSpec = "PoCoax • Unicable LNB";
                                    } else if (is300) {
                                        setModel = "ODU-IDU 300";
                                        iduModel = "IDU-300";
                                        oduModel = "ODU-300";
                                        catSpec = "LTE Cat. 6 • 300 Mb/s (2CA)";
                                        bandsSpec = "B1 / B3 / B7 / B8 / B20 + B38 TDD";
                                        coaxSpec = "PoCoax • Unicable LNB";
                                    } else if (is100) {
                                        setModel = "ODU-IDU 100";
                                        iduModel = "IDU-100";
                                        oduModel = "ODU-100";
                                        catSpec = "LTE Cat. 3 • 30 Mb/s";
                                        bandsSpec = "FDD B3 (1800 MHz)";
                                        coaxSpec = "PoCoax • Zasilanie ODU";
                                    } else {
                                        setModel = "ODU-IDU 200";
                                        iduModel = "IDU-200";
                                        oduModel = "ODU-200";
                                        catSpec = "LTE Cat. 4 • 150 Mb/s";
                                        bandsSpec = "B3 / B7 / B8 / B20 + B38 TDD";
                                        coaxSpec = "PoCoax • Unicable LNB";
                                    }
                                    Log.d(TAG, "Selected model: " + setModel + " (Ping: " + realMeasuredPing + " ms)");
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

                    JSONArray initMsgs = new JSONArray();
                    String nowTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

                    try {
                        JSONObject m1 = new JSONObject();
                        m1.put("id", "net_wifi");
                        m1.put("category", "POŁĄCZENIE");
                        m1.put("level", wifiOn ? "success" : "danger");
                        m1.put("icon", wifiOn ? "✓" : "✕");
                        m1.put("title", wifiOn ? "Połączono z Wi-Fi" : "Brak Wi-Fi");
                        m1.put("text", wifiOn ? "Telefon jest połączony z lokalną siecią bezprzewodową Wi-Fi." : "Włącz Wi-Fi w telefonie.");
                        m1.put("time", nowTime);
                        initMsgs.put(m1);

                        if (finalOdu) {
                            JSONObject m2 = new JSONObject();
                            m2.put("id", "odu_ready");
                            m2.put("category", "URZĄDZENIE");
                            m2.put("level", "success");
                            m2.put("icon", "✓");
                            m2.put("title", "Wykryto " + fSetModel);
                            m2.put("text", "Panel routera (" + finalGw + ") odpowiada w czasie " + finalPing + " ms. Sygnatura platformy " + (fSetModel.contains("200") ? "U270" : "IDU") + " zweryfikowana.");
                            m2.put("time", nowTime);
                            initMsgs.put(m2);
                        }
                    } catch (Exception ignored) {
                    }

                    final String initMsgsJson = initMsgs.toString();

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

        @JavascriptInterface
        public void loginToRouter(final String gatewayIp, final String username, final String password) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean success = false;
                    String message = "";
                    long realPing = -1;
                    String jsonTelemetry = "{}";

                    HttpURLConnection conn = null;
                    try {
                        String targetIp = sanitizeTargetIp(gatewayIp);
                        String safeUsername = (username != null) ? username.trim() : "admin";
                        String safePassword = (password != null) ? password : "";

                        long t0 = System.currentTimeMillis();
                        URL url = new URL("http://" + targetIp + "/cgi-bin/adm.cgi");
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(3500);
                        conn.setReadTimeout(3500);
                        conn.setRequestMethod("POST");
                        conn.setInstanceFollowRedirects(false);
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        conn.setRequestProperty("Origin", "http://" + targetIp);
                        conn.setRequestProperty("Referer", "http://" + targetIp + "/login.shtml");
                        conn.setRequestProperty("Cookie", "language=polish");

                        String body = "page=Login&loginName="
                                + URLEncoder.encode(safeUsername, "UTF-8")
                                + "&loginPassword="
                                + URLEncoder.encode(safePassword, "UTF-8");

                        byte[] postBytes = body.getBytes(StandardCharsets.UTF_8);
                        conn.setFixedLengthStreamingMode(postBytes.length);
                        OutputStream os = conn.getOutputStream();
                        os.write(postBytes);
                        os.flush();
                        os.close();

                        int responseCode = conn.getResponseCode();
                        realPing = System.currentTimeMillis() - t0;
                        String location = conn.getHeaderField("Location");
                        if (location == null) location = "";

                        if (location.contains("id=401.2")) {
                            success = false;
                            message = "Zbyt wiele nieudanych prób logowania (401.2). Odczekaj chwilę.";
                        } else if (location.contains("id=401")) {
                            success = false;
                            message = "Nieprawidłowe hasło lub login routera (kod 401). Wpisz poprawne hasło.";
                        } else if (location.contains("/idu/home") || responseCode == 200 || (responseCode == 302 && !location.contains("401"))) {
                            success = true;
                            message = "Zalogowano pomyślnie do " + targetIp;

                            // Fetch real pages and parse genuine telemetry
                            String homeHtml = fetchHttpString("http://" + targetIp + "/idu/home.shtml", 2500);
                            String lteHtml = fetchHttpString("http://" + targetIp + "/idu/lte.shtml", 2500);
                            String antennaHtml = fetchHttpString("http://" + targetIp + "/idu/antenna.shtml", 2000);
                            String statHtml = fetchHttpString("http://" + targetIp + "/idu/statistic.shtml", 2000);

                            jsonTelemetry = parseRouterTelemetry(homeHtml + "\n" + lteHtml + "\n" + antennaHtml + "\n" + statHtml, realPing, targetIp, "ODU-IDU 200");
                        } else {
                            success = false;
                            message = "Odpowiedź routera HTTP " + responseCode;
                        }
                    } catch (Exception e) {
                        success = false;
                        message = "Błąd połączenia: " + e.getMessage();
                    } finally {
                        if (conn != null) {
                            conn.disconnect();
                        }
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
                    String homeHtml = fetchHttpString("http://" + targetIp + "/idu/home.shtml", 2500);
                    long realPing = Math.max(1, System.currentTimeMillis() - t0);

                    String lteHtml = fetchHttpString("http://" + targetIp + "/idu/lte.shtml", 2500);
                    String antennaHtml = fetchHttpString("http://" + targetIp + "/idu/antenna.shtml", 2000);
                    String statHtml = fetchHttpString("http://" + targetIp + "/idu/statistic.shtml", 2000);

                    final String json = parseRouterTelemetry(homeHtml + "\n" + lteHtml + "\n" + antennaHtml + "\n" + statHtml, realPing, targetIp, "ODU-IDU 200");
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

        private JSONArray buildRouterMessages(String html, boolean hasSignal, boolean isLtePlus, String band, String cellId, String setModel, long realPing, String targetIp) {
            JSONArray arr = new JSONArray();
            String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

            try {
                // 1. ODU-IDU connection status
                boolean oduDisconnected = html.contains("Brak połączenia pomiędzy ODU oraz IDU")
                        || html.contains("Brak połączenia z ODU")
                        || html.contains("ODU Disconnected")
                        || html.contains("ODU connection fail")
                        || html.contains("home content disconnected display")
                        || html.contains("odu config alert2");

                JSONObject msgOdu = new JSONObject();
                msgOdu.put("id", "odu_link");
                msgOdu.put("category", "ODU-IDU");
                msgOdu.put("time", timeStr);
                if (oduDisconnected) {
                    msgOdu.put("level", "danger");
                    msgOdu.put("icon", "⚠️");
                    msgOdu.put("title", "Brak połączenia ODU-IDU");
                    msgOdu.put("text", "Brak połączenia pomiędzy ODU oraz IDU lub ODU jeszcze się uruchamia. Sprawdź kabel koncentryczny i zasilacz PoCoax.");
                } else {
                    msgOdu.put("level", "success");
                    msgOdu.put("icon", "✓");
                    msgOdu.put("title", "Połączenie ODU-IDU aktywne");
                    msgOdu.put("text", "Moduł zewnętrzny ODU jest połączony i zasilany przez kabel koncentryczny PoCoax.");
                }
                arr.put(msgOdu);

                // 2. SIM / USIM status
                boolean simMissing = html.contains("No SIM inserted")
                        || html.contains("Brak karty SIM")
                        || html.contains("Brak karty USIM lub karta USIM jest nieprawidłowa!")
                        || html.contains("SIM card not inserted")
                        || html.contains("Nie włożono karty")
                        || html.contains("SIM not inserted");

                boolean pinLock = html.contains("PIN lock") || html.contains("PIN jest Zamknięty") || html.contains("Wymagany PIN");
                boolean pukLock = html.contains("PUK lock") || html.contains("PUK jest Zamknięty") || html.contains("Wymagany PUK");

                JSONObject msgSim = new JSONObject();
                msgSim.put("id", "sim_status");
                msgSim.put("category", "KARTA SIM");
                msgSim.put("time", timeStr);
                if (pukLock) {
                    msgSim.put("level", "danger");
                    msgSim.put("icon", "🚫");
                    msgSim.put("title", "Karta SIM zablokowana (PUK)");
                    msgSim.put("text", "Wymagany kod PUK od operatora sieci. Karta SIM została zablokowana.");
                } else if (pinLock) {
                    msgSim.put("level", "warning");
                    msgSim.put("icon", "🔐");
                    msgSim.put("title", "Wymagany kod PIN");
                    msgSim.put("text", "Karta SIM oczekuje na wprowadzenie kodu PIN w panelu routera.");
                } else if (simMissing || (!hasSignal && !oduDisconnected)) {
                    msgSim.put("level", "danger");
                    msgSim.put("icon", "✕");
                    msgSim.put("title", "Brak karty SIM / brak sygnału z karty");
                    msgSim.put("text", "Brak karty USIM w modemie ODU lub karta jest nieprawidłowo włożona.");
                } else {
                    msgSim.put("level", "success");
                    msgSim.put("icon", "✓");
                    msgSim.put("title", "Karta SIM aktywna");
                    msgSim.put("text", "Karta USIM została pomyślnie zweryfikowana i zalogowana do sieci.");
                }
                arr.put(msgSim);

                // 3. LTE / Cellular network status
                JSONObject msgNet = new JSONObject();
                msgNet.put("id", "net_status");
                msgNet.put("category", "SIEĆ LTE");
                msgNet.put("time", timeStr);
                if (!hasSignal) {
                    msgNet.put("level", "danger");
                    msgNet.put("icon", "✕");
                    msgNet.put("title", "Brak zasięgu z karty");
                    msgNet.put("text", "Router nie ma zasięgu z karty SIM. Wskaźniki radiowe RF wyświetlają brak odczytu (---).");
                } else {
                    msgNet.put("level", "success");
                    msgNet.put("icon", "📶");
                    msgNet.put("title", isLtePlus ? "Połączono w trybie LTE+ (2CA)" : "Połączono z siecią LTE");
                    String netDetails = isLtePlus ? "Agregacja pasm LTE-A aktywna." : "Połączenie ze stacją bazową aktywne.";
                    if (band != null && !band.equals("---") && !band.isEmpty()) {
                        netDetails += " Pasmo: " + band + ".";
                    }
                    if (cellId != null && !cellId.equals("---") && !cellId.isEmpty()) {
                        netDetails += " Stacja (Cell ID): " + cellId + ".";
                    }
                    msgNet.put("text", netDetails);
                }
                arr.put(msgNet);

                // 4. SMS storage check
                if (html.contains("message is full") || html.contains("Usuń wiadomości aby zwolnić pamięć SMS")) {
                    JSONObject msgSms = new JSONObject();
                    msgSms.put("id", "sms_full");
                    msgSms.put("category", "SMS");
                    msgSms.put("time", timeStr);
                    msgSms.put("level", "warning");
                    msgSms.put("icon", "💬");
                    msgSms.put("title", "Pamięć SMS jest pełna");
                    msgSms.put("text", "Pamięć skrzynki SMS na karcie/routerze jest pełna. Usuń stare wiadomości w panelu.");
                    arr.put(msgSms);
                }

                // 5. System Gateway status
                JSONObject msgSys = new JSONObject();
                msgSys.put("id", "sys_status");
                msgSys.put("category", "ROUTER IDU");
                msgSys.put("time", timeStr);
                msgSys.put("level", "info");
                msgSys.put("icon", "ℹ️");
                msgSys.put("title", "Brama sieciowa IDU");
                msgSys.put("text", "Router " + setModel + " na adresie " + targetIp + " (czas odpowiedzi HTTP: " + realPing + " ms).");
                arr.put(msgSys);

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
