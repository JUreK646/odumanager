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
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private WebView webView;

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

        webView.setBackgroundColor(Color.parseColor("#EAF2FF"));
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new OduBridge(), "OduBridge");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
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
        return "192.168.0.1";
    }

    private String fetchHttpString(String urlString, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
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
                while ((line = reader.readLine()) != null && total < 65536) {
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
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", "");
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
                    long pingMs = -1;

                    String routerModel = "IDU-300";
                    String routerSub = "Zestaw ODU-IDU 300 • Dual-Band Wi-Fi";
                    String specChip = "LTE Cat. 6 • 2.4 / 5 GHz";

                    if (wifiOn) {
                        String[] candidates = gwCandidate.equals("192.168.0.1")
                                ? new String[]{"192.168.0.1"}
                                : new String[]{gwCandidate, "192.168.0.1"};

                        for (String ip : candidates) {
                            long t0 = System.currentTimeMillis();
                            String rootHtml = fetchHttpString("http://" + ip + "/", 1600);
                            long elapsed = System.currentTimeMillis() - t0;

                            if (!rootHtml.isEmpty()) {
                                boolean looksLikeOdu = rootHtml.contains("formLogin")
                                        || rootHtml.contains("/cgi-bin/adm.cgi")
                                        || rootHtml.contains("/idu/home")
                                        || rootHtml.contains("b28n.js")
                                        || rootHtml.contains("ODU")
                                        || rootHtml.contains("IDU");

                                String adminXml = fetchHttpString("http://" + ip + "/lang/en/admin.xml", 1600);
                                if (!adminXml.isEmpty()) {
                                    looksLikeOdu = true;
                                }

                                if (looksLikeOdu) {
                                    oduDetected = true;
                                    gwCandidate = ip;
                                    pingMs = Math.max(1, elapsed);

                                    // Distinguish IDU-300 vs IDU-200 vs IDU-100 based on actual firmware capabilities
                                    boolean has5GhzOrTdd = adminXml.contains("setting 5g menu3")
                                            || adminXml.contains("sysfwnetd2570")
                                            || adminXml.contains("ALR-PT30")
                                            || adminXml.contains("CPE3");

                                    if (has5GhzOrTdd) {
                                        routerModel = "IDU-300";
                                        routerSub = "Router ODU-IDU 300 • Cyfrowy Polsat / Plus";
                                        specChip = "Wi-Fi 2.4/5 GHz • LTE";
                                    } else if (adminXml.contains("ODU") || rootHtml.contains("/idu/home")) {
                                        routerModel = "IDU-200";
                                        routerSub = "Router ODU-IDU 200 • Cyfrowy Polsat / Plus";
                                        specChip = "Wi-Fi 2.4 GHz • LTE";
                                    } else {
                                        routerModel = "IDU-100";
                                        routerSub = "Router ODU-IDU 100 • Cyfrowy Polsat / Plus";
                                        specChip = "Wi-Fi 2.4 GHz • LTE";
                                    }
                                    break;
                                }
                            }
                        }
                    }

                    final boolean finalOdu = oduDetected;
                    final String finalGw = gwCandidate;
                    final long finalPing = pingMs;
                    final String fRouterModel = routerModel;
                    final String fRouterSub = routerSub;
                    final String fSpecChip = specChip;

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String js = String.format(
                                "window.onConnectionStatus && window.onConnectionStatus(%b, %b, '%s', %d, '%s', '%s', '%s');",
                                wifiOn,
                                finalOdu,
                                jsEscape(finalGw),
                                finalPing,
                                jsEscape(fRouterModel),
                                jsEscape(fRouterSub),
                                jsEscape(fSpecChip)
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
                    String message;
                    HttpURLConnection conn = null;
                    try {
                        String targetIp = (gatewayIp != null && !gatewayIp.isEmpty()) ? gatewayIp : "192.168.0.1";
                        URL url = new URL("http://" + targetIp + "/cgi-bin/adm.cgi");
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(3000);
                        conn.setReadTimeout(3000);
                        conn.setRequestMethod("POST");
                        conn.setInstanceFollowRedirects(false);
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        conn.setRequestProperty("Origin", "http://" + targetIp);
                        conn.setRequestProperty("Referer", "http://" + targetIp + "/login.shtml");

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
                        String location = conn.getHeaderField("Location");
                        if (location == null) location = "";

                        if (location.contains("id=401.2")) {
                            success = false;
                            message = "Zbyt wiele nieudanych prób logowania. Odczekaj chwilę i spróbuj ponownie.";
                        } else if (location.contains("id=401") || location.contains("login.shtml")) {
                            success = false;
                            message = "Nieprawidłowa nazwa użytkownika lub hasło routera (" + targetIp + ").";
                        } else if (location.contains("/idu/home") || responseCode == 200 || responseCode == 302) {
                            success = true;
                            message = "Połączono i zalogowano do " + targetIp + " jako " + username + ".";
                        } else {
                            success = false;
                            message = "Odpowiedź routera HTTP " + responseCode + ".";
                        }
                    } catch (Exception e) {
                        success = false;
                        message = "Błąd połączenia z routerem: " + e.getMessage();
                    } finally {
                        if (conn != null) {
                            conn.disconnect();
                        }
                    }

                    final boolean finalSuccess = success;
                    final String finalMsg = message;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String js = String.format(
                                "window.onLoginResult && window.onLoginResult(%b, '%s');",
                                finalSuccess,
                                jsEscape(finalMsg)
                            );
                            webView.evaluateJavascript(js, null);
                        }
                    });
                }
            }).start();
        }
    }
}
