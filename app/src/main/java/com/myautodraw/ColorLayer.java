package com.myautodraw;
import android.graphics.Color;
import android.graphics.PointF;
import java.util.ArrayList;
import java.util.List;

public class ColorLayer {
    public int color;
    public int hue;
    public int saturation;
    public int value;
    public boolean enabled = true;
    public List<List<PointF>> strokes = new ArrayList<>();
    public int pointCount = 0;

    public ColorLayer(int color) { setColor(color); }

    public void setColor(int newColor) {
        this.color = newColor;
        float[] hsv = new float[3];
        Color.colorToHSV(newColor, hsv);
        this.hue = Math.round(hsv[0]);
        this.saturation = Math.round(hsv[1] * 100f);
        this.value = Math.round(hsv[2] * 100f);
    }

    public void updateFromHsv(int h, int s, int v) {
        this.hue = Math.max(0, Math.min(360, h));
        this.saturation = Math.max(0, Math.min(100, s));
        this.value = Math.max(0, Math.min(100, v));
        this.color = Color.HSVToColor(new float[]{(float) this.hue, this.saturation / 100f, this.value / 100f});
    }

    public String getHexString() { return String.format("#%06X", (0xFFFFFF & color)); }
}
