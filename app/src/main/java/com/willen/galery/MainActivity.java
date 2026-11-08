package com.willen.galery;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final String TARGET_URL = "https://willengalery.ct.ws/";
    private static final int FILE_CHOOSER_RESULT_CODE = 1001;

    private WebView webView;
    private LinearLayout splashScreen;
    private ValueCallback<Uri[]> filePathCallback;
    private long lastBackPressTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.parseColor("#131314"));
        window.setNavigationBarColor(Color.parseColor("#131314"));

        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor("#131314"));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#131314"));
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        splashScreen = createSplashScreen();
        rootLayout.addView(splashScreen, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(rootLayout);
        setupWebView();
        webView.loadUrl(TARGET_URL);
    }

    private LinearLayout createSplashScreen() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.parseColor("#131314"));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_willen_logo);
        int size = (int) (110 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(size, size);
        logo.setLayoutParams(logoParams);

        TextView title = new TextView(this);
        title.setText("Willen Galery");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 32, 0, 8);

        TextView subtitle = new TextView(this);
        subtitle.setText("Personal Cloud & AI Photo Vault");
        subtitle.setTextColor(Color.parseColor("#9CA3AF"));
        subtitle.setTextSize(12);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, 48);

        ProgressBar spinner = new ProgressBar(this);

        layout.addView(logo);
        layout.addView(title);
        layout.addView(subtitle);
        layout.addView(spinner);
        return layout;
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        String defaultUA = settings.getUserAgentString();
        settings.setUserAgentString(defaultUA.replace("; wv", ""));

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new AndroidVaultBridge(this), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("willengalery.ct.ws")) {
                    return false;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, request.getUrl());
                    startActivity(intent);
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();

                if (splashScreen.getVisibility() == View.VISIBLE) {
                    splashScreen.animate().alpha(0f).setDuration(300).withEndAction(() ->
                            splashScreen.setVisibility(View.GONE)
                    ).start();
                }

                injectDownloadHooks();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);

                String[] acceptTypes = fileChooserParams.getAcceptTypes();
                boolean isMediaOnly = false;
                if (acceptTypes != null) {
                    for (String t : acceptTypes) {
                        if (t != null && (t.contains("image") || t.contains("video"))) {
                            isMediaOnly = true;
                            break;
                        }
                    }
                }

                if (isMediaOnly) {
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                } else {
                    intent.setType("*/*");
                }

                if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }

                try {
                    startActivityForResult(Intent.createChooser(intent, "Pilih dari Galeri / Penyimpanan"), FILE_CHOOSER_RESULT_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                if (url.startsWith("blob:")) {
                    return;
                }
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    String cookies = CookieManager.getInstance().getCookie(url);
                    request.addRequestHeader("Cookie", cookies);
                    request.addRequestHeader("User-Agent", userAgent);
                    request.setMimeType(mimetype);

                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                    if (url.contains("name=")) {
                        Uri parsed = Uri.parse(url);
                        String paramName = parsed.getQueryParameter("name");
                        if (paramName != null && !paramName.isEmpty()) {
                            fileName = paramName;
                        }
                    }

                    request.setTitle(fileName);
                    request.setDescription("Mengunduh dari Willen Galery...");
                    request.allowScanningByMediaScanner();
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "WillenGalery/" + fileName);

                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                        Toast.makeText(MainActivity.this, "Mengunduh " + fileName + "...", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Gagal mengunduh: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void injectDownloadHooks() {
        String js = "(function() {" +
                "if (window.__willenHooked) return;" +
                "window.__willenHooked = true;" +
                "const origClick = HTMLAnchorElement.prototype.click;" +
                "HTMLAnchorElement.prototype.click = function() {" +
                "  if (this.href && this.href.startsWith('blob:')) {" +
                "    const fileName = this.download || ('Willen_' + Date.now());" +
                "    fetch(this.href).then(r => r.blob()).then(blob => {" +
                "      const reader = new FileReader();" +
                "      reader.onloadend = () => {" +
                "        window.AndroidBridge.saveBase64File(reader.result, fileName, blob.type || 'application/octet-stream');" +
                "      };" +
                "      reader.readAsDataURL(blob);" +
                "    });" +
                "    return;" +
                "  }" +
                "  return origClick.apply(this, arguments);" +
                "};" +
                "const origSubmit = HTMLFormElement.prototype.submit;" +
                "HTMLFormElement.prototype.submit = function() {" +
                "  if (this.id === 'zipDownloadForm') {" +
                "    window.AndroidBridge.showToast('Membuat & mengunduh arsip ZIP...');" +
                "    const fd = new FormData(this);" +
                "    fetch(this.action, { method: 'POST', body: fd }).then(r => r.blob()).then(blob => {" +
                "      const reader = new FileReader();" +
                "      reader.onloadend = () => {" +
                "        const zipName = 'WillenGalery_' + new Date().toISOString().slice(0,19).replace(/[:T-]/g,'') + '.zip';" +
                "        window.AndroidBridge.saveBase64File(reader.result, zipName, 'application/zip');" +
                "      };" +
                "      reader.readAsDataURL(blob);" +
                "    }).catch(() => window.AndroidBridge.showToast('Gagal mengunduh ZIP'));" +
                "    return;" +
                "  }" +
                "  return origSubmit.apply(this, arguments);" +
                "};" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    public class AndroidVaultBridge {
        private final Context context;

        AndroidVaultBridge(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public void showToast(String msg) {
            runOnUiThread(() -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void saveBase64File(String base64DataUrl, String fileName, String mimeType) {
            try {
                String base64Encoded = base64DataUrl.contains(",")
                        ? base64DataUrl.substring(base64DataUrl.indexOf(",") + 1)
                        : base64DataUrl;
                byte[] bytes = Base64.decode(base64Encoded, Base64.DEFAULT);

                String lowerName = fileName.toLowerCase();
                boolean isImage = (mimeType != null && mimeType.startsWith("image/"))
                        || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                        || lowerName.endsWith(".png") || lowerName.endsWith(".webp") || lowerName.endsWith(".gif");
                boolean isVideo = (mimeType != null && mimeType.startsWith("video/"))
                        || lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv")
                        || lowerName.endsWith(".mov") || lowerName.endsWith(".webm");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver resolver = context.getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);

                    Uri collection;
                    if (isImage) {
                        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType != null && !mimeType.isEmpty() ? mimeType : "image/jpeg");
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Willen Galery");
                        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    } else if (isVideo) {
                        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType != null && !mimeType.isEmpty() ? mimeType : "video/mp4");
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Willen Galery");
                        collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    } else {
                        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType != null && !mimeType.isEmpty() ? mimeType : "application/octet-stream");
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Willen Galery");
                        collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    }

                    Uri itemUri = resolver.insert(collection, values);
                    if (itemUri != null) {
                        try (OutputStream os = resolver.openOutputStream(itemUri)) {
                            if (os != null) {
                                os.write(bytes);
                                os.flush();
                            }
                        }
                    }
                } else {
                    File baseDir = isImage
                            ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                            : (isVideo ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                                       : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
                    File targetDir = new File(baseDir, "Willen Galery");
                    if (!targetDir.exists()) targetDir.mkdirs();
                    File outFile = new File(targetDir, fileName);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(bytes);
                        fos.flush();
                    }
                    MediaScannerConnection.scanFile(context, new String[]{outFile.getAbsolutePath()}, null, null);
                }

                if (fileName.endsWith(".zip")) {
                    showToast("ZIP disimpan di Folder Download/Willen Galery");
                }
            } catch (Exception e) {
                showToast("Gagal menyimpan " + fileName);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    ClipData clipData = data.getClipData();
                    int count = clipData.getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = clipData.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        String checkStateJs = "(function() {" +
                "const lb = document.getElementById('lightbox');" +
                "if (lb && !lb.classList.contains('hidden')) { closeLightbox(); return 'HANDLED'; }" +
                "const vp = document.getElementById('vaultPinModal');" +
                "if (vp && !vp.classList.contains('hidden')) { closeVaultPinModal(); return 'HANDLED'; }" +
                "const pm = document.getElementById('profileModal');" +
                "if (pm && !pm.classList.contains('hidden')) { pm.classList.add('hidden'); return 'HANDLED'; }" +
                "const rm = document.getElementById('renameFaceModal');" +
                "if (rm && !rm.classList.contains('hidden')) { rm.classList.add('hidden'); return 'HANDLED'; }" +
                "const sb = document.getElementById('selectionBar');" +
                "if (sb && !sb.classList.contains('hidden')) { clearSelection(); return 'HANDLED'; }" +
                "return 'NONE';" +
                "})();";

        webView.evaluateJavascript(checkStateJs, result -> {
            if (result != null && result.contains("HANDLED")) {
                return;
            }
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                if (System.currentTimeMillis() - lastBackPressTime < 2000) {
                    finish();
                } else {
                    lastBackPressTime = System.currentTimeMillis();
                    Toast.makeText(this, "Tekan sekali lagi untuk keluar dari Willen Galery", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }
}
