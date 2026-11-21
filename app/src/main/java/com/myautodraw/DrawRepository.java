package com.myautodraw;
import java.util.ArrayList;
import java.util.List;

public class DrawRepository {
    private static List<ColorLayer> colorLayers = new ArrayList<>();
    private static long strokeDurationMs = 22L;
    private static float aspectRatio = 1.0f;
    private static boolean autoHsvBuilda = true;

    public static synchronized void setLayers(List<ColorLayer> layers, float ratio) {
        colorLayers = layers != null ? layers : new ArrayList<>();
        aspectRatio = ratio > 0 ? ratio : 1.0f;
    }
    public static synchronized List<ColorLayer> getLayers() { return colorLayers; }
    public static synchronized List<ColorLayer> getEnabledLayers() {
        List<ColorLayer> active = new ArrayList<>();
        for (ColorLayer layer : colorLayers) {
            if (layer.enabled && layer.strokes != null && !layer.strokes.isEmpty()) active.add(layer);
        }
        return active;
    }
    public static float getAspectRatio() { return aspectRatio; }
    public static long getStrokeDurationMs() { return strokeDurationMs; }
    public static void setStrokeDurationMs(long ms) { strokeDurationMs = Math.max(10L, ms); }
    public static boolean isAutoHsvBuilda() { return autoHsvBuilda; }
    public static void setAutoHsvBuilda(boolean enabled) { autoHsvBuilda = enabled; }
}
