package com.myautodraw;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class ImageProcessor {
    public static final int MODE_COLOR = 0;
    public static final int MODE_EDGE = 1;
    public static final int MODE_THRESHOLD = 2;

    public static class ProcessResult {
        public final Bitmap previewBitmap;
        public final List<ColorLayer> layers;
        public final int totalStrokes;
        public final int totalPoints;
        public ProcessResult(Bitmap p, List<ColorLayer> l, int s, int pts) {
            previewBitmap = p; layers = l; totalStrokes = s; totalPoints = pts;
        }
    }

    public static ProcessResult process(Bitmap source, int mode, int threshold, int maxColors, boolean ignoreWhite) {
        int maxDim = (mode == MODE_COLOR) ? 140 : 190;
        float scale = (float) maxDim / Math.max(source.getWidth(), source.getHeight());
        int targetW = Math.max(20, Math.round(source.getWidth() * scale));
        int targetH = Math.max(20, Math.round(source.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, targetW, targetH, true);

        if (mode == MODE_COLOR) return processMultiColor(scaled, targetW, targetH, maxColors, ignoreWhite);
        return processSingleColor(scaled, targetW, targetH, threshold, mode == MODE_EDGE);
    }

    private static ProcessResult processMultiColor(Bitmap scaled, int targetW, int targetH, int maxColors, boolean ignoreWhite) {
        List<int[]> samples = new ArrayList<>();
        int[][] pixelRgb = new int[targetH][targetW];
        boolean[][] validMask = new boolean[targetH][targetW];

        for (int y = 0; y < targetH; y++) {
            for (int x = 0; x < targetW; x++) {
                int p = scaled.getPixel(x, y);
                int a = Color.alpha(p), r = Color.red(p), g = Color.green(p), b = Color.blue(p);
                pixelRgb[y][x] = Color.rgb(r, g, b);
                if (a < 128 || (ignoreWhite && r > 238 && g > 238 && b > 238)) {
                    validMask[y][x] = false;
                } else {
                    validMask[y][x] = true;
                    samples.add(new int[]{r, g, b});
                }
            }
        }
        if (samples.isEmpty()) return new ProcessResult(renderPreview(new ArrayList<>(), targetW, targetH), new ArrayList<>(), 0, 0);

        int k = Math.min(maxColors, samples.size());
        int[][] centroids = new int[k][3];
        Random rng = new Random(42);
        for (int i = 0; i < k; i++) {
            int[] s = samples.get(rng.nextInt(samples.size()));
            centroids[i][0] = s[0]; centroids[i][1] = s[1]; centroids[i][2] = s[2];
        }
        for (int iter = 0; iter < 8; iter++) {
            long[] sumR = new long[k], sumG = new long[k], sumB = new long[k];
            int[] count = new int[k];
            for (int[] s : samples) {
                int best = nearestCentroid(s[0], s[1], s[2], centroids);
                sumR[best] += s[0]; sumG[best] += s[1]; sumB[best] += s[2]; count[best]++;
            }
            for (int i = 0; i < k; i++) {
                if (count[i] > 0) {
                    centroids[i][0] = (int) (sumR[i] / count[i]);
                    centroids[i][1] = (int) (sumG[i] / count[i]);
                    centroids[i][2] = (int) (sumB[i] / count[i]);
                }
            }
        }

        int[][] clusterMap = new int[targetH][targetW];
        for (int y = 0; y < targetH; y++) {
            for (int x = 0; x < targetW; x++) {
                clusterMap[y][x] = validMask[y][x]
                        ? nearestCentroid(Color.red(pixelRgb[y][x]), Color.green(pixelRgb[y][x]), Color.blue(pixelRgb[y][x]), centroids)
                        : -1;
            }
        }

        List<ColorLayer> layers = new ArrayList<>();
        int totalStrokes = 0, totalPoints = 0;
        for (int cIdx = 0; cIdx < k; cIdx++) {
            boolean[][] map = new boolean[targetH][targetW];
            boolean hasPixel = false;
            for (int y = 0; y < targetH; y++) {
                for (int x = 0; x < targetW; x++) {
                    if (clusterMap[y][x] == cIdx) { map[y][x] = true; hasPixel = true; }
                }
            }
            if (!hasPixel) continue;
            ColorLayer layer = new ColorLayer(Color.rgb(centroids[cIdx][0], centroids[cIdx][1], centroids[cIdx][2]));
            layer.strokes = traceStrokes(map, targetW, targetH);
            int pts = 0;
            for (List<PointF> st : layer.strokes) pts += st.size();
            layer.pointCount = pts;
            if (!layer.strokes.isEmpty()) {
                layers.add(layer);
                totalStrokes += layer.strokes.size();
                totalPoints += pts;
            }
        }
        Collections.sort(layers, (a, b) -> Integer.compare(b.pointCount, a.pointCount));
        return new ProcessResult(renderPreview(layers, targetW, targetH), layers, totalStrokes, totalPoints);
    }

    private static int nearestCentroid(int r, int g, int b, int[][] centroids) {
        int bestIdx = 0, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < centroids.length; i++) {
            int dr = r - centroids[i][0], dg = g - centroids[i][1], db = b - centroids[i][2];
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) { bestDist = dist; bestIdx = i; }
        }
        return bestIdx;
    }

    private static ProcessResult processSingleColor(Bitmap scaled, int targetW, int targetH, int threshold, boolean edgeMode) {
        int[][] gray = new int[targetH][targetW];
        for (int y = 0; y < targetH; y++) {
            for (int x = 0; x < targetW; x++) {
                int p = scaled.getPixel(x, y);
                gray[y][x] = Color.alpha(p) < 128 ? 255 : (int) (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p));
            }
        }
        boolean[][] blackMap = new boolean[targetH][targetW];
        if (edgeMode) {
            int edgeThresh = Math.max(25, 280 - threshold);
            for (int y = 1; y < targetH - 1; y++) {
                for (int x = 1; x < targetW - 1; x++) {
                    int gx = -gray[y-1][x-1] + gray[y-1][x+1] - 2*gray[y][x-1] + 2*gray[y][x+1] - gray[y+1][x-1] + gray[y+1][x+1];
                    int gy = -gray[y-1][x-1] - 2*gray[y-1][x] - gray[y-1][x+1] + gray[y+1][x-1] + 2*gray[y+1][x] + gray[y+1][x+1];
                    blackMap[y][x] = Math.sqrt(gx * gx + gy * gy) > edgeThresh;
                }
            }
        } else {
            for (int y = 0; y < targetH; y++)
                for (int x = 0; x < targetW; x++)
                    blackMap[y][x] = gray[y][x] < threshold;
        }
        ColorLayer blackLayer = new ColorLayer(Color.BLACK);
        blackLayer.strokes = traceStrokes(blackMap, targetW, targetH);
        int pts = 0;
        for (List<PointF> st : blackLayer.strokes) pts += st.size();
        blackLayer.pointCount = pts;
        List<ColorLayer> layers = new ArrayList<>();
        if (!blackLayer.strokes.isEmpty()) layers.add(blackLayer);
        return new ProcessResult(renderPreview(layers, targetW, targetH), layers, blackLayer.strokes.size(), pts);
    }

    private static List<List<PointF>> traceStrokes(boolean[][] map, int targetW, int targetH) {
        boolean[][] visited = new boolean[targetH][targetW];
        List<List<PointF>> strokes = new ArrayList<>();
        int[] dx = {1, 1, 0, -1, -1, -1, 0, 1}, dy = {0, 1, 1, 1, 0, -1, -1, -1};
        for (int y = 0; y < targetH; y++) {
            for (int x = 0; x < targetW; x++) {
                if (map[y][x] && !visited[y][x]) {
                    List<PointF> raw = new ArrayList<>();
                    int cx = x, cy = y;
                    while (true) {
                        visited[cy][cx] = true;
                        raw.add(new PointF((float) cx / targetW, (float) cy / targetH));
                        int nextX = -1, nextY = -1;
                        for (int dir = 0; dir < 8; dir++) {
                            int nx = cx + dx[dir], ny = cy + dy[dir];
                            if (nx >= 0 && nx < targetW && ny >= 0 && ny < targetH && map[ny][nx] && !visited[ny][nx]) {
                                nextX = nx; nextY = ny; break;
                            }
                        }
                        if (nextX == -1) break;
                        cx = nextX; cy = nextY;
                    }
                    strokes.add(simplifyStroke(raw));
                }
            }
        }
        return strokes;
    }

    public static Bitmap renderPreview(List<ColorLayer> layers, int targetW, int targetH) {
        int outW = Math.max(200, targetW * 3), outH = Math.max(200, targetH * 3);
        Bitmap preview = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(preview);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeWidth(3.0f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (ColorLayer layer : layers) {
            if (!layer.enabled) continue;
            paint.setColor(layer.color);
            for (List<PointF> stroke : layer.strokes) {
                if (stroke.size() == 1) {
                    PointF p = stroke.get(0);
                    canvas.drawPoint(p.x * outW, p.y * outH, paint);
                } else {
                    for (int i = 0; i < stroke.size() - 1; i++) {
                        PointF p1 = stroke.get(i), p2 = stroke.get(i + 1);
                        canvas.drawLine(p1.x * outW, p1.y * outH, p2.x * outW, p2.y * outH, paint);
                    }
                }
            }
        }
        return preview;
    }

    private static List<PointF> simplifyStroke(List<PointF> pts) {
        if (pts.size() <= 2) return pts;
        List<PointF> out = new ArrayList<>();
        out.add(pts.get(0));
        for (int i = 1; i < pts.size() - 1; i++) {
            PointF prev = out.get(out.size() - 1), curr = pts.get(i), next = pts.get(i + 1);
            float dx1 = curr.x - prev.x, dy1 = curr.y - prev.y, dx2 = next.x - curr.x, dy2 = next.y - curr.y;
            if (Math.abs(dx1 * dy2 - dy1 * dx2) > 1e-6f || out.size() % 5 == 0) out.add(curr);
        }
        out.add(pts.get(pts.size() - 1));
        return out;
    }
}
