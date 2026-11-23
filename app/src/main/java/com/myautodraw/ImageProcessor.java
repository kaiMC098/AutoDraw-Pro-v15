package com.myautodraw;

import android.graphics.*;
import java.util.*;

public class ImageProcessor {
    public static final int MODE_COLOR = 0, MODE_EDGE = 1, MODE_THRESHOLD = 2;
    private static final int MAX_EDGE_PTS = 22;
    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DY = {0, 1, 1, 1, 0, -1, -1, -1};

    public static class ProcessResult {
        public final Bitmap previewBitmap;
        public final List<ColorLayer> layers;
        public final int totalStrokes, totalPoints;
        public ProcessResult(Bitmap p, List<ColorLayer> l, int s, int pts) {
            previewBitmap = p; layers = l; totalStrokes = s; totalPoints = pts;
        }
    }

    public static ProcessResult process(Bitmap src, int mode, int thresh, int maxColors, boolean ignoreWhite) {
        int maxDim = (mode == MODE_COLOR) ? 200 : 260;
        float sc = (float) maxDim / Math.max(src.getWidth(), src.getHeight());
        int w = Math.max(24, Math.round(src.getWidth() * sc));
        int h = Math.max(24, Math.round(src.getHeight() * sc));
        Bitmap baseBmp = Bitmap.createScaledBitmap(src, w, h, true);

        // Tự động đánh giá độ nét của ảnh và tăng sắc nét thích ứng theo từng ảnh
        Bitmap sharpBmp = sharpenAdaptiveByImage(baseBmp, w, h, thresh);

        return (mode == MODE_COLOR)
                ? processColorTopDown1Px(sharpBmp, w, h, thresh, maxColors, ignoreWhite)
                : processMonoTopDown1Px(sharpBmp, w, h, thresh, mode == MODE_EDGE);
    }

    /**
     * Đo chỉ số độ mờ/sắc nét thực tế của ảnh đầu vào để tự điều chỉnh mức làm nét (Unsharp Mask):
     * - Ảnh càng mờ: tự tăng cường độ nét mạnh hơn để giữ rõ viền mắt, tóc, chi tiết nhỏ.
     * - Ảnh đã rõ nét: chỉ làm nét nhẹ để giữ màu tự nhiên, không bị bệt hay rỗ hạt.
     */
    private static Bitmap sharpenAdaptiveByImage(Bitmap src, int w, int h, int userThresh) {
        if (w < 8 || h < 8) return src;
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        double sumGrad = 0.0, sumSqGrad = 0.0;
        int samples = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int c0 = px[y * w + x];
                int cx = px[y * w + (x + 1)];
                int cy = px[(y + 1) * w + x];
                double g0 = 0.299 * Color.red(c0) + 0.587 * Color.green(c0) + 0.114 * Color.blue(c0);
                double gx = 0.299 * Color.red(cx) + 0.587 * Color.green(cx) + 0.114 * Color.blue(cx);
                double gy = 0.299 * Color.red(cy) + 0.587 * Color.green(cy) + 0.114 * Color.blue(cy);
                double grad = Math.hypot(gx - g0, gy - g0);
                sumGrad += grad;
                sumSqGrad += grad * grad;
                samples++;
            }
        }
        double meanGrad = samples > 0 ? sumGrad / samples : 15.0;
        double stdGrad = samples > 0 ? Math.sqrt(Math.max(0.0, sumSqGrad / samples - meanGrad * meanGrad)) : 10.0;

        // Ảnh mờ thường có meanGrad thấp (< 11) và stdGrad thấp (< 14)
        double blurFactor = Math.max(0.0, Math.min(1.0, (14.0 - meanGrad) / 10.0));
        double lowContrastFactor = Math.max(0.0, Math.min(1.0, (18.0 - stdGrad) / 14.0));
        float userFactor = Math.max(0.3f, userThresh / 125f);
        float strength = (float) ((0.65 + 1.35 * blurFactor + 0.45 * lowContrastFactor) * (0.75 + 0.25 * userFactor));

        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int orig = px[idx];
                int a = Color.alpha(orig);
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1 || a < 16) {
                    out[idx] = orig;
                    continue;
                }
                int r0 = Color.red(orig), g0 = Color.green(orig), b0 = Color.blue(orig);
                int rBlur = 0, gBlur = 0, bBlur = 0;
                int[] weights = {1, 2, 1, 2, 4, 2, 1, 2, 1};
                int kIdx = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int p = px[(y + dy) * w + (x + dx)];
                        int wt = weights[kIdx++];
                        rBlur += Color.red(p) * wt;
                        gBlur += Color.green(p) * wt;
                        bBlur += Color.blue(p) * wt;
                    }
                }
                rBlur /= 16; gBlur /= 16; bBlur /= 16;

                int nr = clampByte(Math.round(r0 + strength * (r0 - rBlur)));
                int ng = clampByte(Math.round(g0 + strength * (g0 - gBlur)));
                int nb = clampByte(Math.round(b0 + strength * (b0 - bBlur)));
                out[idx] = Color.argb(a, nr, ng, nb);
            }
        }
        Bitmap sharp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        sharp.setPixels(out, 0, w, 0, 0, w, h);
        return sharp;
    }

    private static int clampByte(int v) { return Math.max(0, Math.min(255, v)); }

    private static ProcessResult processColorTopDown1Px(Bitmap bmp, int w, int h, int thresh, int maxColors, boolean ignoreWhite) {
        int[] r = new int[w * h], g = new int[w * h], b = new int[w * h];
        int[][] gray = new int[h][w];
        boolean[] valid = new boolean[w * h];
        List<Integer> samples = new ArrayList<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = bmp.getPixel(x, y), i = y * w + x;
                r[i] = Color.red(p); g[i] = Color.green(p); b[i] = Color.blue(p);
                boolean trans = Color.alpha(p) < 128;
                gray[y][x] = trans ? 255 : (int) (0.299 * r[i] + 0.587 * g[i] + 0.114 * b[i]);
                boolean skip = trans || (ignoreWhite && r[i] > 240 && g[i] > 240 && b[i] > 240);
                valid[i] = !skip;
                if (!skip) samples.add(i);
            }
        }
        if (samples.isEmpty()) return new ProcessResult(renderPreview(new ArrayList<>(), w, h), new ArrayList<>(), 0, 0);

        int k = Math.min(maxColors, samples.size());
        int[][] cent = seedCentroids(samples, r, g, b, k);
        for (int it = 0; it < 12; it++) {
            long[] sr = new long[k], sg = new long[k], sb = new long[k];
            int[] cnt = new int[k];
            for (int idx : samples) {
                int best = nearestPerceptual(r[idx], g[idx], b[idx], cent);
                sr[best] += r[idx]; sg[best] += g[idx]; sb[best] += b[idx]; cnt[best]++;
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
        for (int i = 0; i < w * h; i++) label[i] = valid[i] ? nearestPerceptual(r[i], g[i], b[i], cent) : -1;
        label = smoothLabels(label, w, h);

        List<ColorLayer> layers = new ArrayList<>();
        Map<ColorLayer, Integer> areaMap = new HashMap<>();
        int minArea = Math.max(2, (w * h) / 8000), totSt = 0, totPts = 0;

        for (int c = 0; c < k; c++) {
            boolean[][] map = new boolean[h][w];
            int area = 0;
            long sr = 0, sg = 0, sb = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int idx = y * w + x;
                    if (label[idx] == c) {
                        map[y][x] = true; area++;
                        sr += r[idx]; sg += g[idx]; sb += b[idx];
                    }
                }
            }
            if (area < minArea) continue;
            ColorLayer layer = new ColorLayer(Color.rgb((int) (sr / area), (int) (sg / area), (int) (sb / area)));
            layer.strokes = scanTopDown1Px(map, w, h);
            int pts = 0;
            for (List<PointF> st : layer.strokes) pts += st.size();
            layer.pointCount = pts;
            if (!layer.strokes.isEmpty()) {
                layers.add(layer); areaMap.put(layer, area);
                totSt += layer.strokes.size(); totPts += pts;
            }
        }

        Collections.sort(layers, (a, b2) -> {
            boolean da = lum(a.color) < 60, db = lum(b2.color) < 60;
            return (da != db) ? (da ? 1 : -1) : Integer.compare(areaMap.get(b2), areaMap.get(a));
        });

        if (thresh > 50) {
            List<List<PointF>> outlines = traceLines(extractEdges(gray, w, h, thresh), w, h);
            if (!outlines.isEmpty()) {
                ColorLayer outLayer = new ColorLayer(Color.rgb(20, 16, 18));
                outLayer.strokes = outlines;
                int pts = 0;
                for (List<PointF> st : outlines) pts += st.size();
                outLayer.pointCount = pts;
                layers.add(outLayer);
                totSt += outlines.size(); totPts += pts;
            }
        }
        return new ProcessResult(renderPreview(layers, w, h), layers, totSt, totPts);
    }

    private static List<List<PointF>> scanTopDown1Px(boolean[][] map, int w, int h) {
        List<List<PointF>> strokes = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            int x = 0;
            while (x < w) {
                if (!map[y][x]) { x++; continue; }
                int x0 = x;
                while (x + 1 < w && map[y][x + 1]) x++;
                float py = (y + 0.5f) / h;
                List<PointF> line = new ArrayList<>(2);
                line.add(new PointF((x0 + 0.5f) / w, py));
                line.add(new PointF(x0 == x ? Math.min(0.999f, (x0 + 0.95f) / w) : (x + 0.5f) / w, py));
                strokes.add(line);
                x++;
            }
        }
        return strokes;
    }

    private static ProcessResult processMonoTopDown1Px(Bitmap bmp, int w, int h, int thresh, boolean edgeMode) {
        int[][] gray = new int[h][w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = bmp.getPixel(x, y);
                gray[y][x] = Color.alpha(p) < 128 ? 255 : (int) (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p));
            }
        List<List<PointF>> strokes;
        if (edgeMode) {
            strokes = traceLines(extractEdges(gray, w, h, thresh), w, h);
        } else {
            boolean[][] map = new boolean[h][w];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) map[y][x] = gray[y][x] < thresh;
            strokes = scanTopDown1Px(map, w, h);
        }
        ColorLayer layer = new ColorLayer(Color.BLACK);
        layer.strokes = strokes;
        int pts = 0;
        for (List<PointF> st : strokes) pts += st.size();
        layer.pointCount = pts;
        List<ColorLayer> layers = strokes.isEmpty() ? new ArrayList<>() : Collections.singletonList(layer);
        return new ProcessResult(renderPreview(layers, w, h), layers, strokes.size(), pts);
    }

    private static boolean[][] extractEdges(int[][] gray, int w, int h, int thresh) {
        boolean[][] edge = new boolean[h][w];
        int limit = Math.max(35, 310 - thresh);
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int gx = -gray[y-1][x-1] + gray[y-1][x+1] - 2*gray[y][x-1] + 2*gray[y][x+1] - gray[y+1][x-1] + gray[y+1][x+1];
                int gy = -gray[y-1][x-1] - 2*gray[y-1][x] - gray[y-1][x+1] + gray[y+1][x-1] + 2*gray[y+1][x] + gray[y+1][x+1];
                edge[y][x] = Math.hypot(gx, gy) > limit;
            }
        }
        thin(edge, w, h);
        return edge;
    }

    private static void thin(boolean[][] m, int w, int h) {
        boolean changed = true;
        for (int g = 0; changed && g < 50; g++) {
            changed = false;
            for (int step = 0; step < 2; step++) {
                List<int[]> rm = new ArrayList<>();
                for (int y = 1; y < h - 1; y++) {
                    for (int x = 1; x < w - 1; x++) {
                        if (!m[y][x]) continue;
                        boolean p2 = m[y-1][x], p3 = m[y-1][x+1], p4 = m[y][x+1], p5 = m[y+1][x+1];
                        boolean p6 = m[y+1][x], p7 = m[y+1][x-1], p8 = m[y][x-1], p9 = m[y-1][x-1];
                        boolean[] q = {p2, p3, p4, p5, p6, p7, p8, p9, p2};
                        int b = 0, a = 0;
                        for (int i = 0; i < 8; i++) { if (q[i]) b++; if (!q[i] && q[i+1]) a++; }
                        if (b < 2 || b > 6 || a != 1) continue;
                        if (step == 0 ? ((p2 && p4 && p6) || (p4 && p6 && p8)) : ((p2 && p4 && p8) || (p2 && p6 && p8))) continue;
                        rm.add(new int[]{x, y});
                    }
                }
                for (int[] p : rm) m[p[1]][p[0]] = false;
                if (!rm.isEmpty()) changed = true;
            }
        }
    }

    private static List<List<PointF>> traceLines(boolean[][] map, int w, int h) {
        boolean[][] vis = new boolean[h][w];
        List<List<PointF>> out = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (!map[y][x] || vis[y][x]) continue;
                    int nb = 0;
                    for (int d = 0; d < 8; d++) {
                        int nx = x + DX[d], ny = y + DY[d];
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h && map[ny][nx]) nb++;
                    }
                    if (nb == 0) { vis[y][x] = true; continue; }
                    if (pass == 0 && nb != 1) continue;
                    List<PointF> s = walkSmooth(map, vis, x, y, w, h);
                    for (int i = 0; i < s.size() - 1; i += MAX_EDGE_PTS - 1)
                        out.add(new ArrayList<>(s.subList(i, Math.min(s.size(), i + MAX_EDGE_PTS))));
                }
            }
        }
        return out;
    }

    private static List<PointF> walkSmooth(boolean[][] map, boolean[][] vis, int sx, int sy, int w, int h) {
        List<int[]> raw = new ArrayList<>();
        int cx = sx, cy = sy, ldx = 0, ldy = 0;
        while (true) {
            vis[cy][cx] = true;
            raw.add(new int[]{cx, cy});
            int bestD = -1;
            float bestS = -1e9f;
            for (int d = 0; d < 8; d++) {
                int nx = cx + DX[d], ny = cy + DY[d];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h || !map[ny][nx] || vis[ny][nx]) continue;
                float sc = (ldx == 0 && ldy == 0) ? 0f : (DX[d] * ldx + DY[d] * ldy);
                if (DX[d] == 0 || DY[d] == 0) sc += 0.15f;
                if (sc > bestS) { bestS = sc; bestD = d; }
            }
            if (bestD < 0) break;
            ldx = DX[bestD]; ldy = DY[bestD]; cx += ldx; cy += ldy;
        }
        int n = raw.size();
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) { xs[i] = raw.get(i)[0] + 0.5f; ys[i] = raw.get(i)[1] + 0.5f; }
        for (int i = 1; i < n - 1; i++) {
            xs[i] = 0.25f * xs[i-1] + 0.5f * xs[i] + 0.25f * xs[i+1];
            ys[i] = 0.25f * ys[i-1] + 0.5f * ys[i] + 0.25f * ys[i+1];
        }
        List<PointF> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) pts.add(new PointF(xs[i] / w, ys[i] / h));
        return pts;
    }

    private static int lum(int c) { return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000; }

    private static int nearestPerceptual(int r, int g, int b, int[][] cent) {
        int best = 0; long minD = Long.MAX_VALUE;
        for (int i = 0; i < cent.length; i++) {
            long rm = (r + cent[i][0]) / 2L, dr = r - cent[i][0], dg = g - cent[i][1], db = b - cent[i][2];
            long d = (((512 + rm) * dr * dr) >> 8) + 4 * dg * dg + (((767 - rm) * db * db) >> 8);
            if (d < minD) { minD = d; best = i; }
        }
        return best;
    }

    private static int[][] seedCentroids(List<Integer> samples, int[] r, int[] g, int[] b, int k) {
        int n = samples.size();
        int[][] cent = new int[k][3];
        Random rng = new Random(7);
        int f = samples.get(rng.nextInt(n));
        cent[0][0] = r[f]; cent[0][1] = g[f]; cent[0][2] = b[f];
        long[] md = new long[n];
        Arrays.fill(md, Long.MAX_VALUE);
        for (int c = 1; c < k; c++) {
            long tot = 0;
            for (int i = 0; i < n; i++) {
                int idx = samples.get(i);
                long dr = r[idx] - cent[c-1][0], dg = g[idx] - cent[c-1][1], db = b[idx] - cent[c-1][2];
                long d = dr * dr + dg * dg + db * db;
                if (d < md[i]) md[i] = d;
                tot += md[i];
            }
            int pick = rng.nextInt(n);
            if (tot > 0) {
                long t = (long) (rng.nextDouble() * tot), acc = 0;
                for (int i = 0; i < n; i++) { acc += md[i]; if (acc > t) { pick = i; break; } }
            }
            int idx = samples.get(pick);
            cent[c][0] = r[idx]; cent[c][1] = g[idx]; cent[c][2] = b[idx];
        }
        return cent;
    }

    private static int[] smoothLabels(int[] label, int w, int h) {
        int[] next = label.clone();
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int own = label[y * w + x];
                if (own < 0) continue;
                int best = own, bestC = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int l = label[(y + dy) * w + (x + dx)];
                        if (l < 0) continue;
                        int c = 0;
                        for (int ey = -1; ey <= 1; ey++)
                            for (int ex = -1; ex <= 1; ex++)
                                if (label[(y + ey) * w + (x + ex)] == l) c++;
                        if (c > bestC) { bestC = c; best = l; }
                    }
                }
                if (best != own && bestC >= 6) next[y * w + x] = best;
            }
        }
        return next;
    }

    public static Bitmap renderPreview(List<ColorLayer> layers, int targetW, int targetH) {
        int outW = Math.max(260, targetW * 3), outH = Math.max(260, targetH * 3);
        Bitmap preview = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(preview);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int idx = 0; idx < layers.size(); idx++) {
            ColorLayer layer = layers.get(idx);
            if (!layer.enabled) continue;
            paint.setColor(layer.color);
            paint.setStrokeWidth((idx == layers.size() - 1 && lum(layer.color) < 40) ? 2.2f : 3.2f);
            for (List<PointF> st : layer.strokes) {
                for (int i = 0; i < st.size() - 1; i++) {
                    PointF p1 = st.get(i), p2 = st.get(i + 1);
                    canvas.drawLine(p1.x * outW, p1.y * outH, p2.x * outW, p2.y * outH, paint);
                }
            }
        }
        return preview;
    }
            }
