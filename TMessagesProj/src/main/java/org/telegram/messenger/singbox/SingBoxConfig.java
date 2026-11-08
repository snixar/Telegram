package org.telegram.messenger.singbox;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * sing-box configuration handling.
 *
 * <p>The application owns the inbound side: whatever {@code inbounds} the user supplied is
 * dropped and replaced with a single loopback SOCKS5 inbound, because Telegram's native layer
 * speaks SOCKS5 to {@code 127.0.0.1}. Everything that decides <em>where</em> the traffic goes
 * ({@code outbounds}, {@code endpoints}, {@code route}, {@code dns}, {@code experimental}) is
 * kept exactly as written, so every outbound type supported by sing-box works.</p>
 */
public final class SingBoxConfig {

    /** Hard cap so a copied subscription blob cannot blow up preferences. */
    public static final int MAX_CONFIG_SIZE = 64 * 1024;

    /** Stable tag of the injected inbound; routing rules may reference it. */
    public static final String INBOUND_TAG = "tg-socks";

    private SingBoxConfig() {
    }

    /**
     * @return {@code null} when the config is usable, otherwise a human readable reason.
     */
    @Nullable
    public static String validate(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) {
            return "config is empty";
        }
        if (json.length() > MAX_CONFIG_SIZE) {
            return "config is larger than 64 KB";
        }
        final JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            return "invalid JSON: " + e.getMessage();
        }
        final JSONArray outbounds = root.optJSONArray("outbounds");
        final JSONArray endpoints = root.optJSONArray("endpoints");
        final int outboundCount = outbounds != null ? outbounds.length() : 0;
        final int endpointCount = endpoints != null ? endpoints.length() : 0;
        if (outboundCount + endpointCount == 0) {
            return "no outbounds or endpoints";
        }
        String error = validateEntries(outbounds, "outbounds");
        if (error == null) {
            error = validateEntries(endpoints, "endpoints");
        }
        return error;
    }

    @Nullable
    private static String validateEntries(@Nullable JSONArray array, @NonNull String field) {
        if (array == null) {
            return null;
        }
        for (int i = 0; i < array.length(); i++) {
            final JSONObject entry = array.optJSONObject(i);
            if (entry == null) {
                return field + "[" + i + "] is not an object";
            }
            final String type = entry.optString("type");
            if (type == null || type.isEmpty()) {
                return field + "[" + i + "] has no type";
            }
        }
        return null;
    }

    /**
     * Produces the configuration actually handed to sing-box: the inbound list is replaced with
     * our loopback SOCKS5 listener and a few defensive defaults are applied.
     */
    @NonNull
    public static String prepare(@NonNull String json, int port) throws JSONException {
        final JSONObject root = new JSONObject(json);

        final JSONArray inbounds = new JSONArray();
        final JSONObject socks = new JSONObject();
        socks.put("type", "socks");
        socks.put("tag", INBOUND_TAG);
        socks.put("listen", "127.0.0.1");
        socks.put("listen_port", port);
        inbounds.put(socks);
        root.put("inbounds", inbounds);

        JSONObject route = root.optJSONObject("route");
        if (route == null) {
            route = new JSONObject();
            root.put("route", route);
        }
        // There is no VpnService here, so sing-box must keep using the system default route.
        route.put("auto_detect_interface", false);

        JSONObject log = root.optJSONObject("log");
        if (log == null) {
            log = new JSONObject();
            root.put("log", log);
        }
        if (!log.has("level")) {
            log.put("level", "warn");
        }
        return root.toString();
    }

    @NonNull
    public static String summarize(@Nullable String json) {
        final Summary summary = summarizeOrNull(json);
        if (summary == null) {
            return "invalid config";
        }
        if (summary.server != null && !summary.server.isEmpty()) {
            return summary.type + " · " + summary.server + (summary.port > 0 ? ":" + summary.port : "");
        }
        return summary.count + (summary.count == 1 ? " outbound" : " outbounds");
    }

    @Nullable
    public static Summary summarizeOrNull(@Nullable String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            final JSONObject root = new JSONObject(json);
            JSONArray array = root.optJSONArray("outbounds");
            if (array == null || array.length() == 0) {
                array = root.optJSONArray("endpoints");
            }
            if (array == null) {
                return null;
            }
            final int count = array.length();
            final JSONObject first = count > 0 ? array.optJSONObject(0) : null;
            if (first == null) {
                return new Summary("", null, 0, count);
            }
            return new Summary(first.optString("type"), first.optString("server"), first.optInt("server_port"), count);
        } catch (JSONException e) {
            return null;
        }
    }

    public static final class Summary {
        public final String type;
        public final String server;
        public final int port;
        public final int count;

        Summary(String type, String server, int port, int count) {
            this.type = type != null ? type : "";
            this.server = server;
            this.port = port;
            this.count = count;
        }
    }
}
