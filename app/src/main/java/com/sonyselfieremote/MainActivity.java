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
    private View controlsOverlay, topStack, bottomStack, connectionPanel, quickControlsBar, selfieSettingsPanel, galleryPanel;
    private TextView selfieSummaryText;
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

        // Android 15+ 会默认 edge-to-edge。让取景真正铺到屏幕边缘，
        // 只把浮动控件避开状态栏/导航栏，不再缩小实时取景本身。
        View root = findViewById(R.id.rootLayout);
        controlsOverlay = findViewById(R.id.controlsOverlay);
        View previewSafe = findViewById(R.id.previewOverlay);
        View showControls = findViewById(R.id.showControlsButton);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            controlsOverlay.setPadding(0, top, 0, bottom);
            previewSafe.setPadding(0, top, 0, bottom);
            android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) showControls.getLayoutParams();
            lp.topMargin = top + (int) (10 * getResources().getDisplayMetrics().density + 0.5f);
            lp.rightMargin = (int) (10 * getResources().getDisplayMetrics().density + 0.5f);
            showControls.setLayoutParams(lp);
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
        topStack = findViewById(R.id.topStack);
        bottomStack = findViewById(R.id.bottomStack);
        connectionPanel = findViewById(R.id.connectionPanel);
        quickControlsBar = findViewById(R.id.quickControlsBar);
        selfieSettingsPanel = findViewById(R.id.selfieSettingsPanel);
        galleryPanel = findViewById(R.id.galleryPanel);
        selfieSummaryText = findViewById(R.id.selfieSummaryText);

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
        findViewById(R.id.connectionToggleButton).setOnClickListener(v ->
                connectionPanel.setVisibility(connectionPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        findViewById(R.id.rotateButton).setOnClickListener(v -> liveView.rotate90());
        ((ToggleButton) findViewById(R.id.fillButton)).setOnCheckedChangeListener((b1, c) -> liveView.setFill(c));
        ((ToggleButton) findViewById(R.id.mirrorButton)).setOnCheckedChangeListener((b1, c) -> liveView.setMirror(c));
        ((ToggleButton) findViewById(R.id.gridButton)).setOnCheckedChangeListener((b1, c) -> liveView.setGrid(c));
        ((ToggleButton) findViewById(R.id.histButton)).setOnCheckedChangeListener((b1, c) -> liveView.setHistogram(c));
        ((ToggleButton) findViewById(R.id.zebraButton)).setOnCheckedChangeListener((b1, c) -> liveView.setZebra(c));
        ((ToggleButton) findViewById(R.id.advancedToggle)).setOnCheckedChangeListener((b1, c) ->
                advancedPanel.setVisibility(c ? View.VISIBLE : View.GONE));
        findViewById(R.id.selfieSettingsToggleButton).setOnClickListener(v ->
                selfieSettingsPanel.setVisibility(selfieSettingsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        findViewById(R.id.hideControlsButton).setOnClickListener(v -> setControlsVisible(false));
        findViewById(R.id.showControlsButton).setOnClickListener(v -> setControlsVisible(true));

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
        installSelfieSummaryHandlers();
        updateSelfieSummary();
    }

    private void setControlsVisible(boolean visible) {
        topStack.setVisibility(visible ? View.VISIBLE : View.GONE);
        bottomStack.setVisibility(visible ? View.VISIBLE : View.GONE);
        findViewById(R.id.showControlsButton).setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private void installSelfieSummaryHandlers() {
        timerSpinner.setOnItemSelectedListener(new SimpleItemSelected(pos -> updateSelfieSummary()));
        burstSpinner.setOnItemSelectedListener(new SimpleItemSelected(pos -> updateSelfieSummary()));
        intervalSpinner.setOnItemSelectedListener(new SimpleItemSelected(pos -> updateSelfieSummary()));
        previewSpinner.setOnItemSelectedListener(new SimpleItemSelected(pos -> updateSelfieSummary()));
    }

    private void updateSelfieSummary() {
        if (selfieSummaryText == null) return;
        String timer = selectedDelaySeconds() + "秒";
        String shots = selectedShotCount() + "张";
        long interval = selectedIntervalMs();
        String gap = interval == 500 ? "0.5秒" : (interval / 1000) + "秒";
        long preview = selectedPreviewMs();
        String review = preview < 0 ? "直到点击" : (preview / 1000) + "秒";
        selfieSummaryText.setText(timer + " · " + shots + " · 间隔" + gap + " · 回看" + review);
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

    private List<String> cameraHostCandidates() {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        SonyEndpointDiscovery.ParsedInput manual = SonyEndpointDiscovery.parseUserInput(cameraIpEdit.getText().toString());
        if (manual != null && manual.host != null) {
            // When the user explicitly enters an IP/host, do NOT silently jump to a
            // different host. We only probe the possible Sony API ports on that host.
            hosts.add(manual.host);
            return new ArrayList<>(hosts);
        }

        String gateway = getWifiGatewayIp();
        if (gateway != null) hosts.add(gateway);
        hosts.add("192.168.122.1");
        hosts.add("192.168.0.1");
        hosts.add("10.0.0.1");
        return new ArrayList<>(hosts);
    }

    private List<String> endpointCandidates() {
        LinkedHashSet<String> endpoints = new LinkedHashSet<>();
        SonyEndpointDiscovery.ParsedInput manual = SonyEndpointDiscovery.parseUserInput(cameraIpEdit.getText().toString());

        if (manual != null && manual.host != null) {
            endpoints.addAll(SonyEndpointDiscovery.buildEndpoints(manual.host, manual.port, manual.exactEndpoint));
            return new ArrayList<>(endpoints);
        }

        for (String host : cameraHostCandidates()) {
            endpoints.addAll(SonyEndpointDiscovery.buildEndpoints(host, null, null));
        }
        return new ArrayList<>(endpoints);
    }

    private static String endpointLabel(String endpoint) {
        try {
            java.net.URI uri = java.net.URI.create(endpoint);
            int p = uri.getPort();
            return uri.getHost() + (p > 0 ? ":" + p : "");
        } catch (Exception e) {
            return endpoint;
        }
    }

    private void connect() {
        if (connected) {
            Toast.makeText(this, "已经连接", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bindProcessToWifi() == null) {
            status("没有检测到 Wi‑Fi。请先连接 A7R II 的 Wi‑Fi。 ");
            return;
        }

        liveProgress.setVisibility(View.VISIBLE);
        status("正在查找 A7R II 的 Camera Remote API…");
        io.execute(() -> {
            SonyCameraApi connectedApi = null;
            String connectedEndpoint = null;
            List<String> errors = new ArrayList<>();
            try {
                List<String> endpoints = endpointCandidates();
                for (String endpoint : endpoints) {
                    if (endpoint == null || endpoint.isEmpty()) continue;
                    final String tryEndpoint = endpoint;
                    status("正在尝试 " + endpointLabel(tryEndpoint) + "…");
                    try {
                        SonyCameraApi candidate = new SonyCameraApi(tryEndpoint);
                        Set<String> caps = candidate.refreshAvailableApis();

                        // A7R II / A7 II-era Smart Remote Embedded initially exposes
                        // startRecMode. Calling it transitions the camera into Remote
                        // Shooting and makes startLiveview / actTakePicture available.
                        candidate.startRecModeIfPossible();
                        caps = candidate.refreshAvailableApis();
                        candidate.setPostview2MIfPossible();

                        if (!candidate.supports("startLiveview")) {
                            throw new Exception("API可达，但 startLiveview 未开放；可用API=" + caps);
                        }

                        connectedApi = candidate;
                        connectedEndpoint = tryEndpoint;
                        break;
                    } catch (Exception e) {
                        String msg = e.getMessage();
                        if (msg == null || msg.isEmpty()) msg = e.getClass().getSimpleName();
                        errors.add(endpointLabel(tryEndpoint) + " → " + msg);
                    }
                }

                if (connectedApi == null || connectedEndpoint == null) {
                    StringBuilder sb = new StringBuilder();
                    int from = Math.max(0, errors.size() - 4);
                    for (int i = from; i < errors.size(); i++) {
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append(errors.get(i));
                    }
                    throw new Exception(sb.length() > 0 ? sb.toString() : "没有发现 Camera Remote API");
                }

                api = connectedApi;
                final String endpoint = connectedEndpoint;
                final String label = endpointLabel(endpoint);
                connected = true;
                ui.post(() -> {
                    cameraIpEdit.setText(label);
                    connectionPanel.setVisibility(View.GONE);
                });
                loadControls();
                String url = api.startLiveview();
                startLiveThread(url);
                status("已连接到 " + label + " · 自拍默认：15秒 / 3张 / 1秒间隔 / 回看5秒");
            } catch (Exception e) {
                String manual = cameraIpEdit.getText().toString().trim();
                String hint = "连接失败：" + e.getMessage()
                        + "。A7R II 常用端点是 192.168.122.1:8080。请确认机身已进入 Applications → Smart Remote Embedded，并停留在等待手机连接/遥控画面。";
                if (!manual.isEmpty()) hint += " 当前输入：" + manual;
                status(hint);
                connected = false;
                api = null;
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
        galleryPanel.setVisibility(View.VISIBLE);
        while (galleryStrip.getChildCount() > 20) galleryStrip.removeViewAt(galleryStrip.getChildCount() - 1);
    }

    private void setSpinner(Spinner sp, List<String> values) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, R.layout.spinner_item, values);
        a.setDropDownViewResource(R.layout.spinner_dropdown_item);
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
