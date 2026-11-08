package pl.odumanager.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
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

import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.parseColor("#FFFFFF"));
            window.setNavigationBarColor(Color.parseColor("#FFFFFF"));
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

        webView.setBackgroundColor(Color.parseColor("#FFFFFF"));
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
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
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
                    boolean oduReachable = false;
                    int httpCode = -1;
                    if (wifiOn) {
                        try {
                            String target = gatewayUrl.startsWith("http") ? gatewayUrl : ("http://" + gatewayUrl);
                            URL url = new URL(target);
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setConnectTimeout(1500);
                            conn.setReadTimeout(1500);
                            conn.setRequestMethod("GET");
                            httpCode = conn.getResponseCode();
                            oduReachable = (httpCode >= 200 && httpCode < 500);
                            conn.disconnect();
                        } catch (Exception ignored) {
                            oduReachable = false;
                        }
                    }
                    final boolean finalOdu = oduReachable;
                    final int finalCode = httpCode;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            webView.evaluateJavascript(
                                "window.onConnectionStatus && window.onConnectionStatus(" + wifiOn + ", " + finalOdu + ", " + finalCode + ");",
                                null
                            );
                        }
                    });
                }
            }).start();
        }
    }
}
