package com.sonyselfieremote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class SonyCameraApi {
    public static final String DEFAULT_ENDPOINT = "http://192.168.122.1:10000/sony/camera";
    private final String endpoint;
    private final AtomicInteger ids = new AtomicInteger(1);
    private final Set<String> available = Collections.synchronizedSet(new HashSet<>());

    public SonyCameraApi() { this(DEFAULT_ENDPOINT); }
    public SonyCameraApi(String endpoint) { this.endpoint = endpoint; }

    public JSONObject call(String method, JSONArray params) throws Exception {
        JSONObject body = new JSONObject();
        body.put("method", method);
        body.put("params", params == null ? new JSONArray() : params);
        body.put("id", ids.getAndIncrement());
        body.put("version", "1.0");

        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setConnectTimeout(2200);
        c.setReadTimeout(8000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = c.getOutputStream()) { os.write(data); }

        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        JSONObject out = new JSONObject(sb.toString());
        if (out.has("error")) throw new CameraException(method + " -> " + out.getJSONArray("error").toString());
        return out;
    }

    public Set<String> refreshAvailableApis() throws Exception {
        JSONObject o = call("getAvailableApiList", new JSONArray());
        available.clear();
        JSONArray arr = o.getJSONArray("result").getJSONArray(0);
        for (int i=0;i<arr.length();i++) available.add(arr.getString(i));
        return new HashSet<>(available);
    }

    public boolean supports(String method) { return available.contains(method); }

    public void startRecModeIfPossible() throws Exception {
        if (supports("startRecMode")) {
            call("startRecMode", new JSONArray());
            Thread.sleep(250);
            refreshAvailableApis();
        }
    }

    public String startLiveview() throws Exception {
        JSONObject o = call("startLiveview", new JSONArray());
        return o.getJSONArray("result").getString(0);
    }

    public void stopLiveview() {
        try { if (supports("stopLiveview")) call("stopLiveview", new JSONArray()); } catch (Exception ignored) {}
    }

    public String takePicture() throws Exception {
        JSONObject o = call("actTakePicture", new JSONArray());
        JSONArray result = o.getJSONArray("result");
        if (result.length()==0) return null;
        Object first = result.get(0);
        if (first instanceof JSONArray) {
            JSONArray urls=(JSONArray)first;
            return urls.length()>0 ? urls.getString(0) : null;
        }
        return String.valueOf(first);
    }

    public void setPostview2MIfPossible() {
        try {
            if (supports("setPostviewImageSize")) call("setPostviewImageSize", new JSONArray().put("2M"));
        } catch (Exception ignored) {}
    }

    public void halfPress() throws Exception { call("actHalfPressShutter", new JSONArray()); }
    public void cancelHalfPress() { try { call("cancelHalfPressShutter", new JSONArray()); } catch(Exception ignored){} }
    public void touchAf(double xPercent, double yPercent) throws Exception {
        call("setTouchAFPosition", new JSONArray().put(xPercent).put(yPercent));
    }

    public List<Object> getAvailableValues(String method) throws Exception {
        JSONObject o = call(method, new JSONArray());
        JSONArray r = o.getJSONArray("result");
        JSONArray a = null;
        for (int i=0;i<r.length();i++) if (r.opt(i) instanceof JSONArray) { a = r.getJSONArray(i); break; }
        if (a == null) return Collections.emptyList();
        List<Object> out = new ArrayList<>();
        for (int i=0;i<a.length();i++) out.add(a.get(i));
        return out;
    }

    public int[] getAvailableExposureCompensationRange() throws Exception {
        JSONObject o = call("getAvailableExposureCompensation", new JSONArray());
        JSONArray r = o.getJSONArray("result");
        if (r.length() < 4) throw new CameraException("Unexpected exposure compensation response: " + r);
        // Sony API order: current, upper, lower, step.
        return new int[]{r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3)};
    }

    public void setExposureMode(String v) throws Exception { call("setExposureMode", new JSONArray().put(v)); }
    public void setShutterSpeed(String v) throws Exception { call("setShutterSpeed", new JSONArray().put(v)); }
    public void setFNumber(String v) throws Exception { call("setFNumber", new JSONArray().put(v)); }
    public void setIso(String v) throws Exception { call("setIsoSpeedRate", new JSONArray().put(v)); }
    public void setExposureCompensation(int v) throws Exception { call("setExposureCompensation", new JSONArray().put(v)); }

    public static byte[] download(String url) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(15000); c.connect();
        try (InputStream is=c.getInputStream(); java.io.ByteArrayOutputStream bos=new java.io.ByteArrayOutputStream()) {
            byte[] buf=new byte[16384]; int n;
            while((n=is.read(buf))!=-1) bos.write(buf,0,n);
            return bos.toByteArray();
        }
    }

    public static class CameraException extends Exception {
        public CameraException(String m){ super(m); }
    }
}
