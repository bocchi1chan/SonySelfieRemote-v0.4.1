package com.sonyselfieremote;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();

    private SonyCameraApi api;
    private LiveViewReader liveReader;
    private LiveViewSurface liveView;
    private TextView statusText, countdownText, previewTitle;
    private ProgressBar liveProgress;
    private LinearLayout galleryStrip, previewThumbStrip;
    private FrameLayout previewOverlay;
    private ImageView previewMain;
    private HorizontalScrollView advancedPanel;
    private Button shutterButton;
    private EditText cameraIpEdit;
    private Network boundWifiNetwork;
    private Spinner modeSpinner, shutterSpinner, apertureSpinner, isoSpinner, evSpinner;
    private Spinner timerSpinner, burstSpinner, intervalSpinner, previewSpinner;

    private volatile boolean connected = false;
    private volatile boolean captureBusy = false;
    private volatile boolean cancelRequested = false;
    private volatile boolean liveRestartPending = false;
    private boolean suppressSpinnerCallbacks = true;
    private Runnable previewDismissRunnable;

    private static class CapturedImage {
        final Bitmap bitmap;
        final Uri uri;
        CapturedImage(Bitmap bitmap, Uri uri) { this.bitmap = bitmap; this.uri = uri; }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        // Android 15+ 会默认把内容延伸到状态栏/导航栏下面。
        // 给根布局应用系统安全区，避免“连接相机”按钮与状态栏重叠。
        View root = findViewById(R.id.rootLayout);
        final int basePadding = (int) (8 * getResources().getDisplayMetrics().density + 0.5f);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    basePadding,
                    basePadding + insets.getSystemWindowInsetTop(),
                    basePadding,
                    basePadding + insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();

        liveView = findViewById(R.id.liveView);
        statusText = findViewById(R.id.statusText);
        countdownText = findViewById(R.id.countdownText);
        liveProgress = findViewById(R.id.liveProgress);
        galleryStrip = findViewById(R.id.galleryStrip);
        previewOverlay = findViewById(R.id.previewOverlay);
        previewMain = findViewById(R.id.previewMain);
        previewTitle = findViewById(R.id.previewTitle);
        previewThumbStrip = findViewById(R.id.previewThumbStrip);
        advancedPanel = findViewById(R.id.advancedPanel);
        shutterButton = findViewById(R.id.shutterButton);
        cameraIpEdit = findViewById(R.id.cameraIpEdit);

        modeSpinner = findViewById(R.id.modeSpinner);
        shutterSpinner = findViewById(R.id.shutterSpinner);
        apertureSpinner = findViewById(R.id.apertureSpinner);
        isoSpinner = findViewById(R.id.isoSpinner);
        evSpinner = findViewById(R.id.evSpinner);
        timerSpinner = findViewById(R.id.timerSpinner);
        burstSpinner = findViewById(R.id.burstSpinner);
        intervalSpinner = findViewById(R.id.intervalSpinner);
        previewSpinner = findViewById(R.id.previewSpinner);

        setSpinner(timerSpinner, Arrays.asList(
                "倒计时 0秒", "倒计时 5秒", "倒计时 10秒", "倒计时 15秒", "倒计时 20秒", "倒计时 30秒"));
        setSpinner(burstSpinner, Arrays.asList("拍 1张", "拍 2张", "拍 3张"));
        setSpinner(intervalSpinner, Arrays.asList("间隔 0.5秒", "间隔 1秒", "间隔 2秒"));
        setSpinner(previewSpinner, Arrays.asList("回看 1秒", "回看 3秒", "回看 5秒", "回看 10秒", "回看直到点击"));
        timerSpinner.setSelection(3);       // 15 seconds
        burstSpinner.setSelection(2);       // 3 shots
        intervalSpinner.setSelection(1);    // 1 second
        previewSpinner.setSelection(2);     // 5 seconds

        setSpinner(modeSpinner, Arrays.asList("—"));
        setSpinner(shutterSpinner, Arrays.asList("—"));
        setSpinner(apertureSpinner, Arrays.asList("—"));
        setSpinner(isoSpinner, Arrays.asList("—"));
        setSpinner(evSpinner, Arrays.asList("—"));

        findViewById(R.id.connectButton).setOnClickListener(v -> connect());
        findViewById(R.id.rotateButton).setOnClickListener(v -> liveView.rotate90());
        ((ToggleButton) findViewById(R.id.mirrorButton)).setOnCheckedChangeListener((b1, c) -> liveView.setMirror(c));
        ((ToggleButton) findViewById(R.id.gridButton)).setOnCheckedChangeListener((b1, c) -> liveView.setGrid(c));
        ((ToggleButton) findViewById(R.id.histButton)).setOnCheckedChangeListener((b1, c) -> liveView.setHistogram(c));
        ((ToggleButton) findViewById(R.id.zebraButton)).setOnCheckedChangeListener((b1, c) -> liveView.setZebra(c));
        ((ToggleButton) findViewById(R.id.advancedToggle)).setOnCheckedChangeListener((b1, c) ->
                advancedPanel.setVisibility(c ? View.VISIBLE : View.GONE));

        findViewById(R.id.focusButton).setOnClickListener(v -> focusOnce());
        shutterButton.setOnClickListener(v -> {
            if (captureBusy) {
                cancelRequested = true;
                status("正在取消本轮自拍…");
            } else {
                startCaptureSequence();
            }
        });

        findViewById(R.id.previewCloseButton).setOnClickListener(v -> hideGroupPreview());
        previewMain.setOnClickListener(v -> hideGroupPreview());
        liveView.setTapListener((x, y) -> tapFocus(x, y));

        installSpinnerHandlers();
    }

    private Network bindProcessToWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    cm.bindProcessToNetwork(n);
                    boundWifiNetwork = n;
                    return n;
                }
            }
        } catch (Exception ignored) {}
        boundWifiNetwork = null;
        return null;
    }

    private String getWifiGatewayIp() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = boundWifiNetwork;
            if (n == null) {
                for (Network candidate : cm.getAllNetworks()) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(candidate);
                    if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        n = candidate;
                        break;
                    }
                }
            }
            if (n == null) return null;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return null;
            for (RouteInfo route : lp.getRoutes()) {
                InetAddress gateway = route.getGateway();
                if (gateway instanceof Inet4Address) {
                    String ip = gateway.getHostAddress();
                    if (ip != null && !ip.isEmpty() && !"0.0.0.0".equals(ip)) return ip;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String extractHost(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("自动查找")) return null;
        try {
            if (v.startsWith("http://") || v.startsWith("https://")) {
                return new URL(v).getHost();
            }
        } catch (Exception ignored) {}
        // 允许输入 192.168.122.1:10000
        int colon = v.indexOf(':');
        if (colon > 0) v = v.substring(0, colon);
        return v;
    }

    private List<String> connectionCandidates() {
        LinkedHashSet<String> ips = new LinkedHashSet<>();
        String manual = extractHost(cameraIpEdit.getText().toString());
        if (manual != null) ips.add(manual);
        String gateway = getWifiGatewayIp();
        if (gateway != null) ips.add(gateway);
        // A7R II / PlayMemories Smart Remote 常见地址。
        ips.add("192.168.122.1");
        ips.add("10.0.0.1");
        return new ArrayList<>(ips);
    }

    private void connect() {
        if (connected) {
            Toast.makeText(this, "已经连接", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bindProcessToWifi() == null) {
            status("没有检测到 Wi‑Fi。请先连接 A7R II 的 Wi‑Fi。");
            return;
        }

        liveProgress.setVisibility(View.VISIBLE);
        status("正在自动查找 A7R II…");
        io.execute(() -> {
            Exception lastError = null;
            String successIp = null;
            try {
                for (String ip : connectionCandidates()) {
                    if (ip == null || ip.isEmpty()) continue;
                    final String tryIp = ip;
                    status("正在尝试 " + tryIp + "…");
                    try {
                        SonyCameraApi candidate = new SonyCameraApi("http://" + tryIp + ":10000/sony/camera");
                        Set<String> caps = candidate.refreshAvailableApis();
                        api = candidate;
                        api.startRecModeIfPossible();
                        caps = api.refreshAvailableApis();
                        api.setPostview2MIfPossible();
                        if (!api.supports("startLiveview")) {
                            throw new Exception("已找到相机，但未开放 startLiveview；请在机身启动 Smart Remote Control。可用API=" + caps);
                        }
                        successIp = tryIp;
                        break;
                    } catch (Exception e) {
                        lastError = e;
                        api = null;
                    }
                }

                if (api == null || successIp == null) {
                    throw lastError != null ? lastError : new Exception("没有找到相机");
                }

                final String connectedIp = successIp;
                connected = true;
                ui.post(() -> cameraIpEdit.setText(connectedIp));
                loadControls();
                String url = api.startLiveview();
                startLiveThread(url);
                status("已连接到 " + connectedIp + " · 自拍默认：15秒 / 3张 / 1秒间隔 / 回看5秒");
            } catch (Exception e) {
                String hint = "连接失败：" + e.getMessage()
                        + "。请确认手机已连接相机 Wi‑Fi；也可手填 192.168.122.1 后重试。";
                status(hint);
                connected = false;
            }
            ui.post(() -> liveProgress.setVisibility(View.GONE));
        });
    }

    private void startLiveThread(String url) {
        if (liveReader != null) liveReader.stop();
        liveReader = new LiveViewReader(url, new LiveViewReader.Listener() {
            @Override public void onFrame(Bitmap f) {
                ui.post(() -> liveView.setFrame(f));
            }
            @Override public void onError(Exception e) {
                status("取景暂时中断，正在尝试恢复…");
                scheduleLiveViewRecovery();
            }
        });
        io.execute(liveReader);
    }

    private void scheduleLiveViewRecovery() {
        if (!connected || liveRestartPending) return;
        liveRestartPending = true;
        ui.postDelayed(() -> {
            if (captureBusy) {
                liveRestartPending = false;
                scheduleLiveViewRecovery();
                return;
            }
            io.execute(() -> {
                try {
                    api.refreshAvailableApis();
                    if (api.supports("startLiveview")) {
                        String url = api.startLiveview();
                        startLiveThread(url);
                        status("实时取景已恢复");
                    }
                } catch (Exception e) {
                    status("实时取景恢复失败：" + e.getMessage());
                } finally {
                    liveRestartPending = false;
                }
            });
        }, 900);
    }

    private void loadControls() {
        suppressSpinnerCallbacks = true;
        loadStringValues("getAvailableExposureMode", modeSpinner);
        loadStringValues("getAvailableShutterSpeed", shutterSpinner);
        loadStringValues("getAvailableFNumber", apertureSpinner);
        loadStringValues("getAvailableIsoSpeedRate", isoSpinner);
        loadEvValues();
        ui.postDelayed(() -> suppressSpinnerCallbacks = false, 700);
    }

    private void loadStringValues(String method, Spinner spinner) {
        io.execute(() -> {
            try {
                if (!api.supports(method)) {
                    setSpinnerUi(spinner, Arrays.asList("不支持"));
                    return;
                }
                List<Object> vals = api.getAvailableValues(method);
                List<String> s = new ArrayList<>();
                for (Object v : vals) s.add(String.valueOf(v));
                if (s.isEmpty()) s.add("—");
                setSpinnerUi(spinner, s);
            } catch (Exception e) {
                setSpinnerUi(spinner, Arrays.asList("读取失败"));
            }
        });
    }

    private void loadEvValues() {
        io.execute(() -> {
            try {
                if (!api.supports("getAvailableExposureCompensation")) {
                    setSpinnerUi(evSpinner, Arrays.asList("不支持"));
                    return;
                }
                int[] range = api.getAvailableExposureCompensationRange();
                int current = range[0], max = range[1], min = range[2], step = Math.max(1, Math.abs(range[3]));
                List<String> s = new ArrayList<>();
                for (int n = min; n <= max; n += step) {
                    s.add(String.format(java.util.Locale.US, "%+.1f", n / 3.0));
                }
                if (s.isEmpty()) s.add("—");
                final int selected = Math.max(0, Math.min(s.size() - 1, (current - min) / step));
                ui.post(() -> {
                    setSpinner(evSpinner, s);
                    evSpinner.setSelection(selected);
                });
            } catch (Exception e) {
                setSpinnerUi(evSpinner, Arrays.asList("读取失败"));
            }
        });
    }

    private void installSpinnerHandlers() {
        modeSpinner.setOnItemSelectedListener(new SimpleItemSelected(pos -> {
            if (!suppressSpinnerCallbacks && connected && api.supports("setExposureMode"))
                asyncSet(() -> api.setExposureMode(String.valueOf(modeSpinner.getItemAtPosition(pos))), "模式");
        }));
        shutterSpinner.setOnItemSelectedListener(new SimpleItemSelected(pos -> {
            if (!suppressSpinnerCallbacks && connected && api.supports("setShutterSpeed"))
                asyncSet(() -> api.setShutterSpeed(String.valueOf(shutterSpinner.getItemAtPosition(pos))), "快门");
        }));
        apertureSpinner.setOnItemSelectedListener(new SimpleItemSelected(pos -> {
            if (!suppressSpinnerCallbacks && connected && api.supports("setFNumber"))
                asyncSet(() -> api.setFNumber(String.valueOf(apertureSpinner.getItemAtPosition(pos))), "光圈");
        }));
        isoSpinner.setOnItemSelectedListener(new SimpleItemSelected(pos -> {
            if (!suppressSpinnerCallbacks && connected && api.supports("setIsoSpeedRate"))
                asyncSet(() -> api.setIso(String.valueOf(isoSpinner.getItemAtPosition(pos))), "ISO");
        }));
        evSpinner.setOnItemSelectedListener(new SimpleItemSelected(pos -> {
            if (!suppressSpinnerCallbacks && connected && api.supports("setExposureCompensation")) {
                Object item = evSpinner.getItemAtPosition(pos);
                try {
                    double ev = Double.parseDouble(String.valueOf(item));
                    int raw = (int) Math.round(ev * 3);
                    asyncSet(() -> api.setExposureCompensation(raw), "曝光补偿");
                } catch (Exception ignored) {}
            }
        }));
    }

    private interface ThrowingAction { void run() throws Exception; }
    private void asyncSet(ThrowingAction a, String label) {
        io.execute(() -> {
            try {
                a.run();
                status(label + " 已设置");
            } catch (Exception e) {
                status(label + " 设置失败：" + e.getMessage());
            }
        });
    }

    private void focusOnce() {
        if (!connected) return;
        io.execute(() -> {
            try {
                if (!api.supports("actHalfPressShutter")) throw new Exception("相机未开放半按快门API");
                api.halfPress();
                Thread.sleep(700);
                api.cancelHalfPress();
                status("AF 完成");
            } catch (Exception e) {
                status("AF失败：" + e.getMessage());
            }
        });
    }

    private void tapFocus(double x, double y) {
        if (!connected || !api.supports("setTouchAFPosition")) return;
        io.execute(() -> {
            try {
                api.touchAf(x, y);
                status(String.format(java.util.Locale.US, "触摸AF %.0f%%, %.0f%%", x, y));
            } catch (Exception e) {
                status("触摸AF失败：" + e.getMessage());
            }
        });
    }

    private int selectedDelaySeconds() {
        return new int[]{0, 5, 10, 15, 20, 30}[timerSpinner.getSelectedItemPosition()];
    }

    private int selectedShotCount() {
        return new int[]{1, 2, 3}[burstSpinner.getSelectedItemPosition()];
    }

    private long selectedIntervalMs() {
        return new long[]{500, 1000, 2000}[intervalSpinner.getSelectedItemPosition()];
    }

    private long selectedPreviewMs() {
        return new long[]{1000, 3000, 5000, 10000, -1}[previewSpinner.getSelectedItemPosition()];
    }

    private void startCaptureSequence() {
        if (!connected) {
            Toast.makeText(this, "请先连接相机", Toast.LENGTH_SHORT).show();
            return;
        }
        if (captureBusy) return;

        final int delay = selectedDelaySeconds();
        final int count = selectedShotCount();
        final long intervalMs = selectedIntervalMs();
        final long previewMs = selectedPreviewMs();

        captureBusy = true;
        cancelRequested = false;
        hideGroupPreview();
        ui.post(() -> shutterButton.setText("取消自拍"));

        io.execute(() -> {
            try {
                for (int s = delay; s > 0; s--) {
                    if (cancelRequested) throw new InterruptedException("已取消");
                    final int n = s;
                    ui.post(() -> countdownText.setText(String.valueOf(n)));
                    status("倒计时 " + s + " 秒");
                    Thread.sleep(1000);
                }
                ui.post(() -> countdownText.setText(""));

                List<String> postviewUrls = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    if (cancelRequested) throw new InterruptedException("已取消");
                    final int shotNumber = i + 1;
                    status("正在拍摄 " + shotNumber + "/" + count);
                    String post = api.takePicture();
                    if (post != null && !post.isEmpty()) postviewUrls.add(post);
                    if (i < count - 1) {
                        long slept = 0;
                        while (slept < intervalMs) {
                            if (cancelRequested) throw new InterruptedException("已取消");
                            long chunk = Math.min(100, intervalMs - slept);
                            Thread.sleep(chunk);
                            slept += chunk;
                        }
                    }
                }

                if (cancelRequested) throw new InterruptedException("已取消");
                List<CapturedImage> group = new ArrayList<>();
                for (int i = 0; i < postviewUrls.size(); i++) {
                    status("正在回传预览 " + (i + 1) + "/" + postviewUrls.size());
                    CapturedImage image = downloadAndSavePostview(postviewUrls.get(i));
                    if (image != null) group.add(image);
                }

                if (!group.isEmpty()) {
                    status("拍摄完成 · 正在检查刚拍的照片");
                    ui.post(() -> showGroupPreview(group, previewMs));
                } else {
                    status("拍摄完成，但相机没有返回可显示的预览图");
                }
            } catch (InterruptedException e) {
                status("本轮自拍已取消");
                ui.post(() -> countdownText.setText(""));
            } catch (Exception e) {
                status("拍摄失败：" + e.getMessage());
                ui.post(() -> countdownText.setText(""));
            } finally {
                captureBusy = false;
                cancelRequested = false;
                ui.post(() -> shutterButton.setText("开始自拍"));
            }
        });
    }

    private CapturedImage downloadAndSavePostview(String url) {
        try {
            byte[] jpeg = SonyCameraApi.download(url);
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            Uri saved = saveJpeg(jpeg);
            if (bmp != null) {
                ui.post(() -> addThumb(bmp, saved));
                return new CapturedImage(bmp, saved);
            }
        } catch (Exception e) {
            status("照片已拍，但某张预览回传失败：" + e.getMessage());
        }
        return null;
    }

    private void showGroupPreview(List<CapturedImage> group, long previewMs) {
        if (group == null || group.isEmpty()) return;
        if (previewDismissRunnable != null) ui.removeCallbacks(previewDismissRunnable);

        previewThumbStrip.removeAllViews();
        int last = group.size() - 1;
        showPreviewImage(group, last);

        int size = (int) (76 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < group.size(); i++) {
            final int index = i;
            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(5, 5, 5, 5);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(group.get(i).bitmap);
            iv.setOnClickListener(v -> showPreviewImage(group, index));
            previewThumbStrip.addView(iv);
        }

        previewOverlay.setVisibility(View.VISIBLE);
        if (previewMs > 0) {
            previewDismissRunnable = this::hideGroupPreview;
            ui.postDelayed(previewDismissRunnable, previewMs);
        } else {
            previewDismissRunnable = null;
        }
    }

    private void showPreviewImage(List<CapturedImage> group, int index) {
        if (index < 0 || index >= group.size()) return;
        previewMain.setImageBitmap(group.get(index).bitmap);
        previewTitle.setText("刚刚拍摄 · 第 " + (index + 1) + "/" + group.size() + " 张");
    }

    private void hideGroupPreview() {
        if (previewDismissRunnable != null) {
            ui.removeCallbacks(previewDismissRunnable);
            previewDismissRunnable = null;
        }
        if (previewOverlay != null) previewOverlay.setVisibility(View.GONE);
        if (previewMain != null) previewMain.setImageDrawable(null);
        status(connected ? "实时取景 · 可以开始下一轮自拍" : "请先连接相机 Wi‑Fi");
    }

    private Uri saveJpeg(byte[] data) throws Exception {
        String name = "SSR_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
                .format(new java.util.Date()) + ".jpg";
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SonySelfieRemote");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("MediaStore insert failed");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new Exception("MediaStore output stream failed");
                os.write(data);
            }
            return uri;
        } else {
            java.io.File dir = new java.io.File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "SonySelfieRemote");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, name);
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) { os.write(data); }
            return Uri.fromFile(f);
        }
    }

    private void addThumb(Bitmap b, Uri uri) {
        ImageView iv = new ImageView(this);
        int size = (int) (66 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(4, 4, 4, 4);
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(b);
        iv.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "image/jpeg");
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "已保存到 Pictures/SonySelfieRemote", Toast.LENGTH_SHORT).show();
            }
        });
        galleryStrip.addView(iv, 0);
        while (galleryStrip.getChildCount() > 20) galleryStrip.removeViewAt(galleryStrip.getChildCount() - 1);
    }

    private void setSpinner(Spinner sp, List<String> values) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
    }

    private void setSpinnerUi(Spinner sp, List<String> values) {
        ui.post(() -> setSpinner(sp, values));
    }

    private void status(String s) {
        ui.post(() -> statusText.setText(s));
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (previewDismissRunnable != null) ui.removeCallbacks(previewDismissRunnable);
        if (liveReader != null) liveReader.stop();
        if (api != null) api.stopLiveview();
        io.shutdownNow();
    }

    private static class SimpleItemSelected implements android.widget.AdapterView.OnItemSelectedListener {
        interface F { void call(int pos); }
        private final F f;
        SimpleItemSelected(F f) { this.f = f; }
        @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { f.call(pos); }
        @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
    }
}
