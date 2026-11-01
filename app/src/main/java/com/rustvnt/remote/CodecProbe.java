package com.rustvnt.remote;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

public final class CodecProbe {
    private CodecProbe() {}
    public static JSONArray decoders(int width, int height, int fps) {
        JSONArray result = new JSONArray();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            String name = info.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            boolean hardware = Build.VERSION.SDK_INT >= 29 ? info.isHardwareAccelerated()
                : !(lower.startsWith("omx.google.") || lower.startsWith("c2.android.") || lower.contains(".sw."));
            for (String type : info.getSupportedTypes()) {
                String codec = type.equalsIgnoreCase("video/avc") ? "h264"
                    : type.equalsIgnoreCase("video/hevc") ? "hevc" : type.equalsIgnoreCase("video/av01") ? "av1" : null;
                if (codec == null || (!hardware && !codec.equals("h264"))) continue;
                try {
                    MediaCodecInfo.VideoCapabilities cap = info.getCapabilitiesForType(type).getVideoCapabilities();
                    int w = align(width, cap.getWidthAlignment()), h = align(height, cap.getHeightAlignment());
                    // Software H.264 remains a bounded 720p fallback even on older devices.
                    if (!hardware) { w = align(Math.min(width, 1280), cap.getWidthAlignment()); h = align(Math.min(height, 720), cap.getHeightAlignment()); }
                    if (!cap.isSizeSupported(w, h)) continue;
                    int rate = Math.min(fps, cap.getSupportedFrameRatesFor(w, h).getUpper().intValue());
                    if (rate < 1) continue;
                    JSONObject d = new JSONObject().put("codec", codec).put("name", name).put("hardware", hardware)
                        .put("maxWidth", cap.getSupportedWidths().getUpper()).put("maxHeight", cap.getSupportedHeights().getUpper())
                        .put("maxFps", rate);
                    result.put(d);
                } catch (RuntimeException | org.json.JSONException ignored) { }
            }
        }
        return result;
    }
    private static int align(int value, int alignment) { return ((value + alignment - 1) / alignment) * alignment; }
}
