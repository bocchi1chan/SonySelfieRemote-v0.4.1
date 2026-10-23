package com.sonyselfieremote;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Endpoint discovery for Sony's legacy ScalarWebAPI / Camera Remote API.
 *
 * A7R II-era Alpha bodies commonly expose:
 *   - Wi-Fi gateway: 192.168.122.1
 *   - Camera API:    http://192.168.122.1:8080/sony/camera
 *   - UPnP descriptor on port 64321
 *
 * Some other Sony devices use port 10000 instead, so we probe rather than
 * hard-code a single port.
 */
public final class SonyEndpointDiscovery {
    private SonyEndpointDiscovery() {}

    public static final class ParsedInput {
        public final String host;
        public final Integer port;
        public final String exactEndpoint;

        ParsedInput(String host, Integer port, String exactEndpoint) {
            this.host = host;
            this.port = port;
            this.exactEndpoint = exactEndpoint;
        }
    }

    public static ParsedInput parseUserInput(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("自动查找")) return null;
        try {
            String normalized = v;
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                normalized = "http://" + normalized;
            }
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return null;
            int p = uri.getPort();
            Integer port = p > 0 ? p : null;
            String path = uri.getPath();
            String exact = null;
            if (path != null && path.contains("/sony/")) {
                exact = normalized;
                if (exact.endsWith("/")) exact = exact.substring(0, exact.length() - 1);
                if (!exact.endsWith("/camera")) {
                    int idx = exact.indexOf("/sony/");
                    if (idx >= 0) exact = exact.substring(0, idx) + "/sony/camera";
                }
            }
            return new ParsedInput(host, port, exact);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static List<String> buildEndpoints(String host, Integer explicitPort, String exactEndpoint) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (exactEndpoint != null && !exactEndpoint.isEmpty()) out.add(exactEndpoint);

        if (host == null || host.isEmpty()) return new ArrayList<>(out);

        // If the user typed a port, respect it first.
        if (explicitPort != null) {
            out.add("http://" + host + ":" + explicitPort + "/sony/camera");
        }

        // Sony Alpha / Smart Remote Embedded commonly advertises the real base URL
        // through a UPnP device description on 64321.
        out.addAll(discoverFromDescriptors(host));

        // A7R II / A7 II era Alpha bodies are commonly on 8080.
        out.add("http://" + host + ":8080/sony/camera");
        // Other Camera Remote API devices commonly use 10000.
        out.add("http://" + host + ":10000/sony/camera");

        return new ArrayList<>(out);
    }

    private static List<String> discoverFromDescriptors(String host) {
        LinkedHashSet<String> endpoints = new LinkedHashSet<>();
        String[] paths = new String[]{
                "/DmsRmtDesc.xml",
                "/dd.xml",
                "/DmsDesc.xml",
                "/DmsDescPush.xml"
        };
        for (String path : paths) {
            String descriptorUrl = "http://" + host + ":64321" + path;
            try {
                String xml = httpGetText(descriptorUrl, 700, 1200);
                if (xml == null || xml.isEmpty()) continue;
                endpoints.addAll(extractCameraEndpoints(xml));
            } catch (Exception ignored) {
                // Probe only; failure here is normal on some bodies/modes.
            }
        }
        return new ArrayList<>(endpoints);
    }

    static List<String> extractCameraEndpoints(String xml) {
        LinkedHashSet<String> bases = new LinkedHashSet<>();

        // Covers both <av:X_ScalarWebAPI_ActionList_URL> and unprefixed variants.
        Pattern action = Pattern.compile("<[^>]*X_ScalarWebAPI_ActionList_URL[^>]*>\\s*([^<]+)\\s*</[^>]+>", Pattern.CASE_INSENSITIVE);
        Matcher am = action.matcher(xml);
        while (am.find()) bases.add(am.group(1).trim());

        Pattern base = Pattern.compile("<[^>]*X_ScalarWebAPI_BaseURL[^>]*>\\s*([^<]+)\\s*</[^>]+>", Pattern.CASE_INSENSITIVE);
        Matcher bm = base.matcher(xml);
        while (bm.find()) bases.add(bm.group(1).trim());

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String b : bases) {
            if (!b.toLowerCase(Locale.US).startsWith("http")) continue;
            String clean = b;
            while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
            if (clean.endsWith("/camera")) {
                out.add(clean);
            } else if (clean.endsWith("/sony")) {
                out.add(clean + "/camera");
            } else if (clean.contains("/sony/")) {
                int idx = clean.indexOf("/sony/");
                out.add(clean.substring(0, idx) + "/sony/camera");
            }
        }
        return new ArrayList<>(out);
    }

    private static String httpGetText(String url, int connectTimeout, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectTimeout);
        c.setReadTimeout(readTimeout);
        c.setRequestMethod("GET");
        c.setUseCaches(false);
        c.connect();
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) return null;
        InputStream is = c.getInputStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        } finally {
            c.disconnect();
        }
        return sb.toString();
    }
}
