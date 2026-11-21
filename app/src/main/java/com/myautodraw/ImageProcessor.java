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
 * Tách ảnh thành các lớp màu và tạo nét vẽ.
 * - Chế độ màu / ngưỡng: tô theo từng hàng (kiểu "rắn") -> nét thẳng, đầy, ít bị đứt.
 * - Chế độ nét (edge): làm mỏng đường viền rồi đi theo đường -> nét liền mạch.
 */
public class ImageProcessor {
    public static final int MODE_COLOR = 0;
    public static final int MODE_EDGE = 1;
    public static final int MODE_THRESHOLD = 2;

    /** Số điểm tối đa của một nét (nét dài được chia nhỏ, vẫn nối liền nhau). */
    private static final int MAX_POINTS_PER_STROKE = 40;

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
        int maxDim = (mode == MODE_COLOR) ? 170 : 230;
        float scale = (float) maxDim / Math.max(source.getWidth(), source.getHeight());
        int targetW = Math.max(20, Math.round(source.getWidth() * scale));
        int targetH = Math.max(20, Math.round(source.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, targetW, targetH, true);

        if (mode == MODE_COLOR) return processMultiColor(scaled, targetW, targetH, maxColors, ignoreWhite);
        return processSingleColor(scaled, targetW, targetH, threshold, mode == MODE_EDGE);
    }

    // ------------------------------------------------------------------ MÀU
    private static ProcessResult processMultiColor(Bitmap scaled, int w, int h, int maxColors, boolean ignoreWhite) {
        int[] r = new int[w * h], g = new int[w * h], b = new int[w * h];
        boolean[] valid = new boolean[w * h];
        List<Integer> sampleIdx = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = scaled.getPixel(x, y);
                int i = y * w + x;
                r[i] = Color.red(p); g[i] = Color.green(p); b[i] = Color.blue(p);
                boolean skip = Color.alpha(p) < 128 || (ignoreWhite && r[i] > 238 && g[i] > 238 && b[i] > 238);
                valid[i] = !skip;
                if (!skip) sampleIdx.add(i);
            }
        }
        if (sampleIdx.isEmpty()) {
            return new ProcessResult(renderPreview(new ArrayList<ColorLayer>(), w, h), new ArrayList<ColorLayer>(), 0, 0);
        }

        int n = sampleIdx.size();
        int k = Math.min(maxColors, n);
        int[][] cent = seedCentroids(sampleIdx, r, g, b, k);
        for (int iter = 0; iter < 10; iter++) {
            long[] sr = new long[k], sg = new long[k], sb = new long[k];
            int[] cnt = new int[k];
            for (int idx : sampleIdx) {
                int best = nearest(r[idx], g[idx], b[idx], cent);
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
        for (int i = 0; i < w * h; i++) label[i] = valid[i] ? nearest(r[i], g[i], b[i], cent) : -1;
        label = smoothLabels(label, w, h, 2);

        List<ColorLayer> layers = new ArrayList<>();
        Map<ColorLayer, Integer> areaOf = new HashMap<>();
        int minArea = Math.max(3, (w * h) / 5000);
        int totalStrokes = 0, totalPoints = 0;
        for (int c = 0; c < k; c++) {
            boolean[][] map = new boolean[h][w];
            int area = 0;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    if (label[y * w + x] == c) { map[y][x] = true; area++; }
            if (area < minArea) continue;
            ColorLayer layer = new ColorLayer(Color.rgb(cent[c][0], cent[c][1], cent[c][2]));
            layer.strokes = fillStrokes(map, w, h);
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
        // Vẽ vùng lớn trước, màu rất tối (nét viền) vẽ sau cùng để đè lên.
        final Map<ColorLayer, Integer> areas = areaOf;
        Collections.sort(layers, (a, c2) -> {
            boolean da = luminance(a.color) < 60, dc = luminance(c2.color) < 60;
            if (da != dc) return da ? 1 : -1;
            return Integer.compare(areas.get(c2), areas.get(a));
        });
        return new ProcessResult(renderPreview(layers, w, h), layers, totalStrokes, totalPoints);
    }

    private static int luminance(int color) {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000;
    }

    /** k-means++ (cố định seed) để chọn tâm màu ban đầu đa dạng hơn. */
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

    private static int nearest(int r, int g, int b, int[][] cent) {
        int bestIdx = 0, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < cent.length; i++) {
            int dr = r - cent[i][0], dg = g - cent[i][1], db = b - cent[i][2];
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) { bestDist = dist; bestIdx = i; }
        }
        return bestIdx;
    }

    /** Lọc đa số 3x3: xoá điểm nhiễu lẻ, làm mượt biên các vùng màu. */
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

    // ------------------------------------------------------- ĐEN TRẮNG / NÉT
    private static ProcessResult processSingleColor(Bitmap scaled, int w, int h, int threshold, boolean edgeMode) {
        int[][] gray = new int[h][w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = scaled.getPixel(x, y);
                gray[y][x] = Color.alpha(p) < 128 ? 255
                        : (int) (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p));
            }
        boolean[][] map = new boolean[h][w];
        List<List<PointF>> strokes;
        if (edgeMode) {
            int edgeThresh = Math.max(25, 280 - threshold);
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int gx = -gray[y-1][x-1] + gray[y-1][x+1] - 2*gray[y][x-1] + 2*gray[y][x+1] - gray[y+1][x-1] + gray[y+1][x+1];
                    int gy = -gray[y-1][x-1] - 2*gray[y-1][x] - gray[y-1][x+1] + gray[y+1][x-1] + 2*gray[y+1][x] + gray[y+1][x+1];
                    map[y][x] = Math.sqrt((double) gx * gx + (double) gy * gy) > edgeThresh;
                }
            }
            thin(map, w, h);
            despeckle(map, w, h);
            strokes = traceLines(map, w, h);
        } else {
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    map[y][x] = gray[y][x] < threshold;
            despeckle(map, w, h);
            strokes = fillStrokes(map, w, h);
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
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                if (!m[y][x]) continue;
                int c = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h && m[ny][nx]) c++;
                    }
                if (c == 0) rm.add(new int[]{x, y});
            }
        for (int[] p : rm) m[p[1]][p[0]] = false;
    }

    // ------------------------------------------------------------ TÔ KIỂU RẮN
    /**
     * Tô vùng bằng các đường ngang nối nhau thành hình "con rắn":
     * mỗi hàng đi hết một đoạn liên tục rồi bước xuống hàng kế và đi ngược lại.
     */
    private static List<List<PointF>> fillStrokes(boolean[][] map, int w, int h) {
        List<List<PointF>> done = new ArrayList<>();
        List<Open> open = new ArrayList<>();
        List<List<int[]>> rowRuns = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            List<int[]> rr = new ArrayList<>();
            int x = 0;
            while (x < w) {
                if (!map[y][x]) { x++; continue; }
                int s = x;
                while (x + 1 < w && map[y][x + 1]) x++;
                rr.add(new int[]{s, x});
                x++;
            }
            rowRuns.add(rr);
        }
        for (int y = 0; y < h; y++) {
            List<int[]> runs = rowRuns.get(y);
            boolean[] used = new boolean[open.size()];
            List<Open> next = new ArrayList<>();
            for (int[] run : runs) {
                int x0 = run[0], x1 = run[1];
                int pick = -1;
                for (int i = 0; i < open.size(); i++) {
                    if (!used[i] && open.get(i).x >= x0 - 1 && open.get(i).x <= x1 + 1) { pick = i; break; }
                }
                if (pick >= 0) {
                    used[pick] = true;
                    Open o = open.get(pick);
                    int entry = Math.max(x0, Math.min(x1, o.x));
                    o.pts.add(pt(entry, y, w, h));
                    int near = (entry - x0 <= x1 - entry) ? x0 : x1;
                    int far = (near == x0) ? x1 : x0;
                    if (entry != near) o.pts.add(pt(near, y, w, h));
                    if (near != far) o.pts.add(pt(far, y, w, h));
                    o.x = (near != far) ? far : near;
                    next.add(o);
                } else {
                    Open o = new Open();
                    // Nhìn trước hàng kế tiếp để chọn hướng đi, giúp đường chéo không bị đứt.
                    boolean endLeft = false;
                    if (y + 1 < h) {
                        for (int[] r2 : rowRuns.get(y + 1)) {
                            if (r2[1] >= x0 - 1 && r2[0] <= x1 + 1) { endLeft = (r2[0] + r2[1]) < (x0 + x1); break; }
                        }
                    }
                    if (endLeft) {
                        o.pts.add(pt(x1, y, w, h));
                        if (x1 != x0) o.pts.add(pt(x0, y, w, h));
                        o.x = x0;
                    } else {
                        o.pts.add(pt(x0, y, w, h));
                        if (x1 != x0) o.pts.add(pt(x1, y, w, h));
                        o.x = x1;
                    }
                    next.add(o);
                }
            }
            for (int i = 0; i < open.size(); i++) if (!used[i]) addChunks(done, open.get(i).pts);
            open = next;
        }
        for (Open o : open) addChunks(done, o.pts);
        return done;
    }

    private static class Open {
        final List<PointF> pts = new ArrayList<>();
        int x;
    }

    private static PointF pt(int x, int y, int w, int h) {
        return new PointF((x + 0.5f) / w, (y + 0.5f) / h);
    }

    /** Chia nét dài thành nhiều đoạn, đoạn sau bắt đầu đúng chỗ đoạn trước kết thúc. */
    private static void addChunks(List<List<PointF>> out, List<PointF> pts) {
        if (pts.isEmpty()) return;
        int i = 0;
        while (i < pts.size() - 1 || i == 0) {
            int end = Math.min(pts.size(), i + MAX_POINTS_PER_STROKE);
            out.add(new ArrayList<>(pts.subList(i, end)));
            if (end >= pts.size()) break;
            i = end - 1;
        }
    }

    // ------------------------------------------------------ LÀM MỎNG + ĐI NÉT
    /** Zhang-Suen: làm mỏng đường viền còn 1 điểm ảnh. */
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

    private static List<List<PointF>> traceLines(boolean[][] map, int w, int h) {
        boolean[][] visited = new boolean[h][w];
        List<List<PointF>> strokes = new ArrayList<>();
        // Bắt đầu từ đầu mút trước, rồi tới các điểm còn lại (vòng kín).
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (!map[y][x] || visited[y][x]) continue;
                    if (pass == 0 && neighborCount(map, x, y, w, h) != 1) continue;
                    List<int[]> raw = walk(map, visited, x, y, w, h);
                    if (raw.size() >= 3) strokes.add(toStroke(raw, w, h));
                }
            }
        }
        List<List<PointF>> out = new ArrayList<>();
        for (List<PointF> s : strokes) addChunks(out, s);
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
                if (DX[d] == 0 || DY[d] == 0) score += 0.1f;
                if (score > bestScore) { bestScore = score; bestDir = d; }
            }
            if (bestDir < 0) break;
            ldx = DX[bestDir]; ldy = DY[bestDir];
            cx += ldx; cy += ldy;
        }
        return raw;
    }

    /** Làm mượt nhẹ (trung bình 3 điểm) rồi bỏ các điểm thẳng hàng. */
    private static List<PointF> toStroke(List<int[]> raw, int w, int h) {
        int n = raw.size();
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = raw.get(i)[0] + 0.5f; ys[i] = raw.get(i)[1] + 0.5f;
        }
        float[] sx = xs.clone(), sy = ys.clone();
        for (int i = 1; i < n - 1; i++) {
            sx[i] = 0.25f * xs[i - 1] + 0.5f * xs[i] + 0.25f * xs[i + 1];
            sy[i] = 0.25f * ys[i - 1] + 0.5f * ys[i] + 0.25f * ys[i + 1];
        }
        List<PointF> out = new ArrayList<>();
        out.add(new PointF(sx[0] / w, sy[0] / h));
        for (int i = 1; i < n - 1; i++) {
            PointF prev = out.get(out.size() - 1);
            float x1 = sx[i] / w - prev.x, y1 = sy[i] / h - prev.y;
            float x2 = sx[i + 1] / w - sx[i] / w, y2 = sy[i + 1] / h - sy[i] / h;
            float cross = Math.abs(x1 * y2 - y1 * x2);
            if (cross > 2e-6f || out.size() % 8 == 0) out.add(new PointF(sx[i] / w, sy[i] / h));
        }
        out.add(new PointF(sx[n - 1] / w, sy[n - 1] / h));
        return out;
    }

    // --------------------------------------------------------------- XEM TRƯỚC
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
}
