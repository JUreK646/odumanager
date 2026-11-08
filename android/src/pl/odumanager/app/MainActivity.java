package pl.odumanager.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.util.Log;
import android.net.wifi.WifiManager;
import android.net.DhcpInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private WebView webView;
    private ConnectivityManager.NetworkCallback networkCallback;

    static {
        // Automatically accept and maintain session cookies across HttpURLConnection requests
        CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
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
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setBackgroundColor(Color.parseColor("#EAF2FF"));
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
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

    private static String jsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
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
                    String gwCandidate = getDetectedGatewayIp();
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

                                    // Genuine hardware model fingerprinting:
                                    // ODU-IDU 300 features:
                                    // - Extended dictionary with Mesh Wi-Fi (adminXml.length() > 71000 bytes, mesh_tplg; on IDU-200 it is ~68238 bytes)
                                    // - Direct full login page in root (rootHtml.length() > 5000 bytes; on IDU-200 root is a ~341-byte short redirect)
                                    // - Model strings: IDU-300, ODU-300, PT30, PT40, CPE3
                                    boolean hasMesh = adminXml.contains("mesh_tplg") || adminXml.contains("menu3_mesh");
                                    boolean has300Tag = adminXml.contains("IDU-300") || adminXml.contains("ODU-300") || rootHtml.contains("IDU-300") || adminXml.contains("PT30") || adminXml.contains("PT40") || adminXml.contains("CPE3");
                                    boolean is300Size = adminXml.length() > 71000 || rootHtml.length() > 5000;

                                    boolean is300 = hasMesh || has300Tag || is300Size;

                                    boolean is100 = !is300 && (adminXml.contains("U100")
                                            || adminXml.contains("IDU-100")
                                            || adminXml.contains("ODU-100"));

                                    boolean is200 = !is300 && !is100;

                                    Log.d("OduManager", "Probing IP: " + ip + " rootHtml len: " + rootHtml.length() + " adminXml len: " + adminXml.length());
                                    Log.d("OduManager", "Fingerprint check: is300=" + is300 + " (mesh=" + hasMesh + ", size=" + is300Size + ", tag=" + has300Tag + "), is200=" + is200 + ", is100=" + is100);

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
                                        bandsSpec = "FDD B3 / B7 / B20";
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
                                        bandsSpec = "FDD B3 / B7 / B20";
                                        coaxSpec = "PoCoax • Unicable LNB";
                                    }
                                    Log.d("OduManager", "Selected model: " + setModel + " (Ping: " + realMeasuredPing + " ms)");
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

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String js = String.format(
                                "window.onConnectionStatus && window.onConnectionStatus(%b, %b, '%s', %d, '%s', '%s', '%s', '%s', '%s', '%s');",
                                wifiOn,
                                finalOdu,
                                jsEscape(finalGw),
                                finalPing,
                                jsEscape(fSetModel),
                                jsEscape(fIduModel),
                                jsEscape(fOduModel),
                                jsEscape(fCatSpec),
                                jsEscape(fBandsSpec),
                                jsEscape(fCoaxSpec)
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
                        String targetIp = (gatewayIp != null && !gatewayIp.isEmpty()) ? gatewayIp : "192.168.0.1";
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
                                + URLEncoder.encode(username, "UTF-8")
                                + "&loginPassword="
                                + URLEncoder.encode(password, "UTF-8");

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

                            jsonTelemetry = parseRouterTelemetry(homeHtml + "\n" + lteHtml + "\n" + antennaHtml + "\n" + statHtml, realPing);
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
                                "window.onLoginResult && window.onLoginResult(%b, '%s', %s);",
                                finalSuccess,
                                jsEscape(finalMsg),
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
                    String targetIp = (gatewayIp != null && !gatewayIp.isEmpty()) ? gatewayIp : "192.168.0.1";
                    long t0 = System.currentTimeMillis();
                    String homeHtml = fetchHttpString("http://" + targetIp + "/idu/home.shtml", 2500);
                    long realPing = Math.max(1, System.currentTimeMillis() - t0);

                    String lteHtml = fetchHttpString("http://" + targetIp + "/idu/lte.shtml", 2500);
                    String statHtml = fetchHttpString("http://" + targetIp + "/idu/statistic.shtml", 2000);

                    final String json = parseRouterTelemetry(homeHtml + "\n" + lteHtml + "\n" + statHtml, realPing);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            webView.evaluateJavascript("window.onTelemetryUpdate && window.onTelemetryUpdate(" + json + ");", null);
                        }
                    });
                }
            }).start();
        }

        private String parseRouterTelemetry(String html, long realPing) {
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

            return "{"
                    + "\"realPingMs\":" + realPing + ","
                    + "\"signalLevel\":" + signalLevel + ","
                    + "\"hasSignal\":" + hasSignal + ","
                    + "\"isLtePlus\":" + isLtePlus + ","
                    + "\"rsrp\":\"" + jsEscape(rsrp) + "\","
                    + "\"rsrq\":\"" + jsEscape(rsrq) + "\","
                    + "\"sinr\":\"" + jsEscape(sinr) + "\","
                    + "\"rssi\":\"" + jsEscape(rssi) + "\","
                    + "\"band\":\"" + jsEscape(band) + "\","
                    + "\"cellId\":\"" + jsEscape(cellId) + "\""
                    + "}";
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
                        String targetIp = (gatewayIp != null && !gatewayIp.isEmpty()) ? gatewayIp : "192.168.0.1";
                        URL url = new URL("http://" + targetIp + "/cgi-bin/adm.cgi");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(3000);
                        conn.setReadTimeout(3000);
                        conn.setRequestMethod("POST");
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        conn.setRequestProperty("Referer", "http://" + targetIp + "/idu/lte.shtml");

                        String body = "page=lteband&band=" + URLEncoder.encode(bandSelection, "UTF-8");
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
