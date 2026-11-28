package com.myautodraw;

import java.util.ArrayList;
import java.util.List;

public class DrawRepository {
    private static List<ColorLayer> colorLayers = new ArrayList<>();
    private static long strokeDurationMs = 28L;
    private static float aspectRatio = 1.0f;
    private static int gridW = 240, gridH = 240;
    private static boolean autoHsvBuilda = false;

    public static int boxX = 120, boxY = 340, boxW = 540, boxH = 540;
    public static int hsvX = 240, hsvY = 720, hsvW = 460, hsvH = 260;
    public static int colorBtnX = 740, colorBtnY = 1850;
    public static int brushBtnX = 130, brushBtnY = 1850;
    public static boolean hasSavedPositions = false;

    public static synchronized void setLayers(List<ColorLayer> layers, float ratio, int w, int h) {
        colorLayers = layers != null ? layers : new ArrayList<>();
        aspectRatio = ratio > 0 ? ratio : 1.0f;
        gridW = Math.max(24, w);
        gridH = Math.max(24, h);
    }

    public static synchronized void setLayers(List<ColorLayer> layers, float ratio) {
        setLayers(layers, ratio, 240, Math.max(24, Math.round(240f / (ratio > 0 ? ratio : 1f))));
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
    public static int getGridW() { return gridW; }
    public static int getGridH() { return gridH; }
    public static long getStrokeDurationMs() { return strokeDurationMs; }
    public static void setStrokeDurationMs(long ms) { strokeDurationMs = Math.max(12L, ms); }
    public static boolean isAutoHsvBuilda() { return autoHsvBuilda; }
    public static void setAutoHsvBuilda(boolean enabled) { autoHsvBuilda = enabled; }
}
