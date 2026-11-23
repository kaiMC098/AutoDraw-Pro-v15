package com.myautodraw;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Bộ xử lý ảnh v15 (@ellenjoe023):
 * - Quét và vẽ từ trên xuống dưới (y = 0 -> H - 1) theo từng hàng 1 pixel chuẩn xác.
 * - Mỗi dải màu liên tục trên 1 hàng pixel là một đường thẳng độc lập, không bị gấp khúc zig-zag
 *   nên không bao giờ bị mất nét hay đứt mảng màu giữa chừng.
 */
public class ImageProcessor {
    public static final int MODE_COLOR = 0;
    public static final int MODE_EDGE = 1;
    public static final int MODE_THRESHOLD = 2;

    /** Giới hạn điểm tối đa mỗi nét viền mảnh */
    private static final int MAX_EDGE_POINTS = 22;

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
        int maxDim = (mode == MODE_COLOR) ? 190 : 250;
        float scale = (float) maxDim / Math.max(source.getWidth(), source.getHeight());
        int targetW = Math.max(24, Math.round(source.getWidth() * scale));
        int targetH = Math.max(24, Math.round(source.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, targetW, targetH, true);

        if (mode == MODE_COLOR) {
            return processMultiColorTopDown1Px(scaled, targetW, targetH, threshold, maxColors, ignoreWhite);
        }
        return processSingleColorTopDown1Px(scaled, targetW, targetH, threshold, mode == MODE_EDGE);
    }

    // --------------------------------------------------- VẼ ĐỦ MÀU: TỪ TRÊN XUỐNG DƯỚI THEO 1 PIXEL
    private static ProcessResult processMultiColorTopDown1Px(
            Bitmap scaled, int w, int h, int threshold, int maxColors, boolean ignoreWhite) {
        int[] r = new int[w * h], g = new int[w * h], b = new int[w * h];
        int[][] gray = new int[h][w];
        boolean[] valid = new boolean[w * h];
        List<Integer> sampleIdx = new ArrayList<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = scaled.getPixel(x, y);
                int i = y * w + x;
                r[i] = Color.red(p);
                g[i] = Color.green(p);
                b[i] = Color.blue(p);
                boolean transparent = Color.alpha(p) < 128;
                gray[y][x] = transparent ? 255 : (int) (0.299 * r[i] + 0.587 * g[i] + 0.114 * b[i]);

                boolean skip = transparent || (ignoreWhite && r[i] > 240 && g[i] > 240 && b[i] > 240);
                valid[i] = !skip;
                if (!skip) sampleIdx.add(i);
            }
        }
        if (sampleIdx.isEmpty()) {
            return new ProcessResult(renderPreview(new ArrayList<>(), w, h), new ArrayList<>(), 0, 0);
        }

        int n = sampleIdx.size();
        int k = Math.min(maxColors, n);
        int[][] cent = seedCentroids(sampleIdx, r, g, b, k);
        for (int iter = 0; iter < 12; iter++) {
            long[] sr = new long[k], sg = new long[k], sb = new long[k];
            int[] cnt = new int[k];
            for (int idx : sampleIdx) {
                int best = nearestPerceptual(r[idx], g[idx], b[idx], cent);
                sr[best] += r[idx];
                sg[best] += g[idx];
                sb[best] += b[idx];
                cnt[best]++;
            }
            for (int c = 0; c < k; c++) {
                if (cnt[c] > 0) {
                    cent[c][0] = (int) (sr[c] / cnt[c]);
                    cent[c][1] = (int) (sg[c] / cnt[c]);
                    cent[c][2] = (int) (sb[c] / cnt[c]);
                }
            }
        }

        int[] label = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            label[i] = valid[i] ? nearestPerceptual(r[i], g[i], b[i], cent) : -1;
        }
        label = smoothLabels(label, w, h, 1);

        List<ColorLayer> layers = new ArrayList<>();
        Map<ColorLayer, Integer> areaOf = new HashMap<>();
        int minArea = Math.max(2, (w * h) / 8000);
        int totalStrokes = 0, totalPoints = 0;

        for (int c = 0; c < k; c++) {
            boolean[][] map = new boolean[h][w];
            int area = 0;
            long sumR = 0, sumG = 0, sumB = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int idx = y * w + x;
                    if (label[idx] == c) {
                        map[y][x] = true;
                        area++;
                        sumR += r[idx];
                        sumG += g[idx];
                        sumB += b[idx];
                    }
                }
            }
            if (area < minArea) continue;

            int exactColor = Color.rgb((int) (sumR / area), (int) (sumG / area), (int) (sumB / area));
            ColorLayer layer = new ColorLayer(exactColor);

            // Quét từ trên xuống dưới (y = 0 -> h - 1), từng hàng 1 pixel một
            layer.strokes = scanTopToBottom1Pixel(map, w, h);

            int pts = 0;
            for (List<PointF> st : layer.strokes) pts += st.size();
            layer.pointCount = pts;
            if (!layer.strokes.isEmpty()) {
                layers.add(layer);
                areaOf.put(layer, area);
                totalStrokes += layer.strokes.size();
                totalPoints += pts;
            }
        }

        final Map<ColorLayer, Integer> areas = areaOf;
        Collections.sort(layers, (a, c2) -> {
            boolean da = luminance(a.color) < 60, dc = luminance(c2.color) < 60;
            if (da != dc) return da ? 1 : -1;
            return Integer.compare(areas.get(c2), areas.get(a));
        });

        if (threshold > 50) {
            boolean[][] lineMap = extractCleanEdges(gray, w, h, threshold);
            List<List<PointF>> outlineStrokes = traceLinesTopDown(lineMap, w, h);
            if (!outlineStrokes.isEmpty()) {
                ColorLayer outlineLayer = new ColorLayer(Color.rgb(20, 16, 18));
                outlineLayer.strokes = outlineStrokes;
                int pts = 0;
                for (List<PointF> st : outlineStrokes) pts += st.size();
                outlineLayer.pointCount = pts;
                layers.add(outlineLayer);
                totalStrokes += outlineStrokes.size();
                totalPoints += pts;
            }
        }

        return new ProcessResult(renderPreview(layers, w, h), layers, totalStrokes, totalPoints);
    }

    /**
     * Quét từ trên xuống dưới (y = 0 -> h - 1) theo từng 1 pixel:
     * Mỗi đoạn pixel liên tiếp [x0..x1] trên hàng y tạo thành 1 nét thẳng (x0, y) -> (x1, y).
     */
    private static List<List<PointF>> scanTopToBottom1Pixel(boolean[][] map, int w, int h) {
        List<List<PointF>> strokes = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            int x = 0;
            while (x < w) {
                if (!map[y][x]) {
                    x++;
                    continue;
                }
                int x0 = x;
                while (x + 1 < w && map[y][x + 1]) {
                    x++;
                }
                int x1 = x;

                List<PointF> line = new ArrayList<>(2);
                float py = (y + 0.5f) / h;
                if (x0 == x1) {
                    float px = (x0 + 0.5f) / w;
                    float pxEnd = Math.min(0.999f, (x0 + 0.95f) / w);
                    line.add(new PointF(px, py));
                    line.add(new PointF(pxEnd, py));
                } else {
                    line.add(new PointF((x0 + 0.5f) / w, py));
                    line.add(new PointF((x1 + 0.5f) / w, py));
                }
                strokes.add(line);
                x++;
            }
        }
        return strokes;
    }

    private static boolean[][] extractCleanEdges(int[][] gray, int w, int h, int threshold) {
        boolean[][] edgeMap = new boolean[h][w];
        int edgeThresh = Math.max(35, 310 - threshold);
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int gx = -gray[y - 1][x - 1] + gray[y - 1][x + 1]
                        - 2 * gray[y][x - 1] + 2 * gray[y][x + 1]
                        - gray[y + 1][x - 1] + gray[y + 1][x + 1];
                int gy = -gray[y - 1][x - 1] - 2 * gray[y - 1][x] - gray[y - 1][x + 1]
                        + gray[y + 1][x - 1] + 2 * gray[y + 1][x] + gray[y + 1][x + 1];
                double mag = Math.sqrt((double) gx * gx + (double) gy * gy);
                edgeMap[y][x] = mag > edgeThresh;
            }
        }
        thin(edgeMap, w, h);
        despeckle(edgeMap, w, h);
        return edgeMap;
    }

    private static int luminance(int color) {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000;
    }

    private static int nearestPerceptual(int r, int g, int b, int[][] cent) {
        int bestIdx = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < cent.length; i++) {
            long rMean = (r + cent[i][0]) / 2L;
            long dr = r - cent[i][0];
            long dg = g - cent[i][1];
            long db = b - cent[i][2];
            long dist = (((512 + rMean) * dr * dr) >> 8) + 4 * dg * dg + (((767 - rMean) * db * db) >> 8);
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static int[][] seedCentroids(List<Integer> sampleIdx, int[] r, int[] g, int[] b, int k) {
        int n = sampleIdx.size();
        int[][] cent = new int[k][3];
        Random rng = new Random(7);
        int first = sampleIdx.get(rng.nextInt(n));
        cent[0][0] = r[first]; cent[0][1] = g[first]; cent[0][2] = b[first];
        long[] minD = new long[n];
        for (int i = 0; i < n; i++) minD[i] = Long.MAX_VALUE;
        for (int c = 1; c < k; c++) {
            long total = 0;
            for (int i = 0; i < n; i++) {
                int idx = sampleIdx.get(i);
                long dr = r[idx] - cent[c - 1][0], dg = g[idx] - cent[c - 1][1], db = b[idx] - cent[c - 1][2];
                long d = dr * dr + dg * dg + db * db;
                if (d < minD[i]) minD[i] = d;
                total += minD[i];
            }
            int pick = n - 1;
            if (total > 0) {
                long target = (long) (rng.nextDouble() * total), acc = 0;
                for (int i = 0; i < n; i++) { acc += minD[i]; if (acc > target) { pick = i; break; } }
            } else {
                pick = rng.nextInt(n);
            }
            int idx = sampleIdx.get(pick);
            cent[c][0] = r[idx]; cent[c][1] = g[idx]; cent[c][2] = b[idx];
        }
        return cent;
    }

    private static int[] smoothLabels(int[] label, int w, int h, int passes) {
        int[] cur = label;
        for (int pass = 0; pass < passes; pass++) {
            int[] next = cur.clone();
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int own = cur[y * w + x];
                    if (own < 0) continue;
                    int bestLabel = own, bestCount = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) continue;
                            int l = cur[(y + dy) * w + (x + dx)];
                            if (l < 0) continue;
                            int c = 0;
                            for (int ey = -1; ey <= 1; ey++)
                                for (int ex = -1; ex <= 1; ex++)
                                    if ((ex != 0 || ey != 0) && cur[(y + ey) * w + (x + ex)] == l) c++;
                            if (c > bestCount) { bestCount = c; bestLabel = l; }
                        }
                    }
                    if (bestLabel != own && bestCount >= 5) next[y * w + x] = bestLabel;
                }
            }
            cur = next;
        }
        return cur;
    }

    private static ProcessResult processSingleColorTopDown1Px(Bitmap scaled, int w, int h, int threshold, boolean edgeMode) {
        int[][] gray = new int[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = scaled.getPixel(x, y);
                gray[y][x] = Color.alpha(p) < 128 ? 255
                        : (int) (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p));
            }
        }
        boolean[][] map = new boolean[h][w];
        List<List<PointF>> strokes;
        if (edgeMode) {
            map = extractCleanEdges(gray, w, h, threshold);
            strokes = traceLinesTopDown(map, w, h);
        } else {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    map[y][x] = gray[y][x] < threshold;
                }
            }
            strokes = scanTopToBottom1Pixel(map, w, h);
        }
        ColorLayer layer = new ColorLayer(Color.BLACK);
        layer.strokes = strokes;
        int pts = 0;
        for (List<PointF> st : strokes) pts += st.size();
        layer.pointCount = pts;
        List<ColorLayer> layers = new ArrayList<>();
        if (!strokes.isEmpty()) layers.add(layer);
        return new ProcessResult(renderPreview(layers, w, h), layers, strokes.size(), pts);
    }

    private static void despeckle(boolean[][] m, int w, int h) {
        List<int[]> rm = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!m[y][x]) continue;
                int c = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h && m[ny][nx]) c++;
                    }
                }
                if (c == 0) rm.add(new int[]{x, y});
            }
        }
        for (int[] p : rm) m[p[1]][p[0]] = false;
    }

    private static void thin(boolean[][] m, int w, int h) {
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 60) {
            changed = false;
            for (int step = 0; step < 2; step++) {
                List<int[]> rm = new ArrayList<>();
                for (int y = 1; y < h - 1; y++) {
                    for (int x = 1; x < w - 1; x++) {
                        if (!m[y][x]) continue;
                        boolean p2 = m[y-1][x], p3 = m[y-1][x+1], p4 = m[y][x+1], p5 = m[y+1][x+1];
                        boolean p6 = m[y+1][x], p7 = m[y+1][x-1], p8 = m[y][x-1], p9 = m[y-1][x-1];
                        boolean[] q = {p2, p3, p4, p5, p6, p7, p8, p9, p2};
                        int bCount = 0, a = 0;
                        for (int i = 0; i < 8; i++) { if (q[i]) bCount++; if (!q[i] && q[i + 1]) a++; }
                        if (bCount < 2 || bCount > 6 || a != 1) continue;
                        if (step == 0) { if ((p2 && p4 && p6) || (p4 && p6 && p8)) continue; }
                        else { if ((p2 && p4 && p8) || (p2 && p6 && p8)) continue; }
                        rm.add(new int[]{x, y});
                    }
                }
                for (int[] p : rm) m[p[1]][p[0]] = false;
                if (!rm.isEmpty()) changed = true;
            }
        }
    }

    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DY = {0, 1, 1, 1, 0, -1, -1, -1};

    private static int neighborCount(boolean[][] m, int x, int y, int w, int h) {
        int c = 0;
        for (int d = 0; d < 8; d++) {
            int nx = x + DX[d], ny = y + DY[d];
            if (nx >= 0 && nx < w && ny >= 0 && ny < h && m[ny][nx]) c++;
        }
        return c;
    }

    private static List<List<PointF>> traceLinesTopDown(boolean[][] map, int w, int h) {
        boolean[][] visited = new boolean[h][w];
        List<List<PointF>> strokes = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (!map[y][x] || visited[y][x]) continue;
                    if (pass == 0 && neighborCount(map, x, y, w, h) != 1) continue;
                    List<int[]> raw = walk(map, visited, x, y, w, h);
                    if (raw.size() >= 3) strokes.add(toSmoothStroke(raw, w, h));
                }
            }
        }
        List<List<PointF>> out = new ArrayList<>();
        for (List<PointF> s : strokes) {
            int i = 0;
            while (i < s.size() - 1 || i == 0) {
                int end = Math.min(s.size(), i + MAX_EDGE_POINTS);
                out.add(new ArrayList<>(s.subList(i, end)));
                if (end >= s.size()) break;
                i = end - 1;
            }
        }
        return out;
    }

    private static List<int[]> walk(boolean[][] map, boolean[][] visited, int sx, int sy, int w, int h) {
        List<int[]> raw = new ArrayList<>();
        int cx = sx, cy = sy, ldx = 0, ldy = 0;
        while (true) {
            visited[cy][cx] = true;
            raw.add(new int[]{cx, cy});
            int bestDir = -1;
            float bestScore = -1e9f;
            for (int d = 0; d < 8; d++) {
                int nx = cx + DX[d], ny = cy + DY[d];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h || !map[ny][nx] || visited[ny][nx]) continue;
                float score = (ldx == 0 && ldy == 0) ? 0f : (DX[d] * ldx + DY[d] * ldy);
                if (DX[d] == 0 || DY[d] == 0) score += 0.15f;
                if (score > bestScore) { bestScore = score; bestDir = d; }
            }
            if (bestDir < 0) break;
            ldx = DX[bestDir]; ldy = DY[bestDir];
            cx += ldx; cy += ldy;
        }
        return raw;
    }

    private static List<PointF> toSmoothStroke(List<int[]> raw, int w, int h) {
        int n = raw.size();
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = raw.get(i)[0] + 0.5f;
            ys[i] = raw.get(i)[1] + 0.5f;
        }
        for (int pass = 0; pass < 2; pass++) {
            float[] nx = xs.clone(), ny = ys.clone();
            for (int i = 1; i < n - 1; i++) {
                nx[i] = 0.25f * xs[i - 1] + 0.5f * xs[i] + 0.25f * xs[i + 1];
                ny[i] = 0.25f * ys[i - 1] + 0.5f * ys[i] + 0.25f * ys[i + 1];
            }
            xs = nx;
            ys = ny;
        }
        List<PointF> out = new ArrayList<>();
        out.add(new PointF(xs[0] / w, ys[0] / h));
        for (int i = 1; i < n - 1; i++) {
            PointF prev = out.get(out.size() - 1);
            float x1 = xs[i] / w - prev.x, y1 = ys[i] / h - prev.y;
            float x2 = xs[i + 1] / w - xs[i] / w, y2 = ys[i + 1] / h - ys[i] / h;
            float cross = Math.abs(x1 * y2 - y1 * x2);
            if (cross > 1.5e-6f || out.size() % 6 == 0) {
                out.add(new PointF(xs[i] / w, ys[i] / h));
            }
        }
        out.add(new PointF(xs[n - 1] / w, ys[n - 1] / h));
        return out;
    }

    public static Bitmap renderPreview(List<ColorLayer> layers, int targetW, int targetH) {
        int outW = Math.max(260, targetW * 3), outH = Math.max(260, targetH * 3);
        Bitmap preview = Bitmap.createBitmap(outW, outH, Bitmap.Config
