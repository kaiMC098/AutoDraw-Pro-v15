package com.myautodraw;

import android.graphics.*;
import java.util.*;

public class ImageProcessor {
  public static final int MODE_COLOR = 0, MODE_EDGE = 1, MODE_THRESHOLD = 2;
  private static final int MAX_EDGE_PTS = 26;
  private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
  private static final int[] DY = {0, 1, 1, 1, 0, -1, -1, -1};

  public static class ProcessResult {
    public final Bitmap previewBitmap;
    public final List<ColorLayer> layers;
    public final int totalStrokes, totalPoints, gridW, gridH;
    public ProcessResult(Bitmap p, List<ColorLayer> l, int s, int pts, int gw, int gh) {
      previewBitmap = p; layers = l; totalStrokes = s; totalPoints = pts; gridW = gw; gridH = gh;
    }
  }

  public static ProcessResult process(Bitmap src, int mode, int thresh, int maxColors, boolean ignoreWhite) {
    int maxDim = (mode == MODE_COLOR) ? (150 + Math.round((thresh / 255f) * 150f)) : 280;
    float sc = (float) maxDim / Math.max(src.getWidth(), src.getHeight());
    int w = Math.max(24, Math.round(src.getWidth() * sc));
    int h = Math.max(24, Math.round(src.getHeight() * sc));
    Bitmap baseBmp = Bitmap.createScaledBitmap(src, w, h, true);
    Bitmap sharpBmp = sharpenAndSnapEdges(baseBmp, w, h);

    return (mode == MODE_COLOR)
        ? processColorExact1Px(sharpBmp, w, h, maxColors, ignoreWhite)
        : processMono1Px(sharpBmp, w, h, thresh, mode == MODE_EDGE);
  }

  // Làm sắc nét thích ứng + khóa biên Shock Filter để xóa mờ viền khi thu nhỏ mà không tạo viền đen giả
  private static Bitmap sharpenAndSnapEdges(Bitmap src, int w, int h) {
    if (w < 6 || h < 6) return src;
    int[] px = new int[w * h];
    src.getPixels(px, 0, w, 0, 0, w, h);

    double sumG = 0.0; int cnt = 0;
    for (int y = 1; y < h - 1; y++) {
      for (int x = 1; x < w - 1; x++) {
        int c0 = px[y * w + x], cx = px[y * w + x + 1], cy = px[(y + 1) * w + x];
        sumG += Math.hypot(lum(cx) - lum(c0), lum(cy) - lum(c0)); cnt++;
      }
    }
    double meanG = cnt > 0 ? sumG / cnt : 14.0;
    float strength = (float) (0.72 + 0.75 * Math.max(0.0, Math.min(1.0, (16.0 - meanG) / 11.0)));

    int[] out = new int[w * h];
    int[] wt = {1, 2, 1, 2, 4, 2, 1, 2, 1};
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int idx = y * w + x, orig = px[idx], a = Color.alpha(orig);
        if (x == 0 || y == 0 || x == w - 1 || y == h - 1 || a < 16) { out[idx] = orig; continue; }
        int r0 = Color.red(orig), g0 = Color.green(orig), b0 = Color.blue(orig);
        int rb = 0, gb = 0, bb = 0, k = 0;
        int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;
        for (int dy = -1; dy <= 1; dy++) {
          for (int dx = -1; dx <= 1; dx++) {
            int p = px[(y + dy) * w + (x + dx)], ww = wt[k++];
            int pr = Color.red(p), pg = Color.green(p), pb = Color.blue(p);
            rb += pr * ww; gb += pg * ww; bb += pb * ww;
            if (pr < minR) minR = pr; if (pr > maxR) maxR = pr;
            if (pg < minG) minG = pg; if (pg > maxG) maxG = pg;
            if (pb < minB) minB = pb; if (pb > maxB) maxB = pb;
          }
        }
        float sr = r0 + strength * (r0 - rb / 16f);
        float sg = g0 + strength * (g0 - gb / 16f);
        float sb = b0 + strength * (b0 - bb / 16f);

        int amp = Math.max(maxR - minR, Math.max(maxG - minG, maxB - minB));
        if (amp > 24) {
          float snap = Math.min(0.58f, (amp - 24f) / 95f);
          float tr = (r0 >= 0.5f * (minR + maxR)) ? maxR : minR;
          float tg = (g0 >= 0.5f * (minG + maxG)) ? maxG : minG;
          float tb = (b0 >= 0.5f * (minB + maxB)) ? maxB : minB;
          sr = sr * (1f - snap) + tr * snap;
          sg = sg * (1f - snap) + tg * snap;
          sb = sb * (1f - snap) + tb * snap;
        }

        int nr = clamp(Math.round(sr), Math.max(0, minR - 8), Math.min(255, maxR + 8));
        int ng = clamp(Math.round(sg), Math.max(0, minG - 8), Math.min(255, maxG + 8));
        int nb = clamp(Math.round(sb), Math.max(0, minB - 8), Math.min(255, maxB + 8));
        out[idx] = Color.argb(a, nr, ng, nb);
      }
    }
    Bitmap sharp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    sharp.setPixels(out, 0, w, 0, 0, w, h);
    return sharp;
  }

  private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

  private static boolean[] detectOuterWhiteBg(int[] r, int[] g, int[] b, int[] alpha, int w, int h) {
    boolean[] bg = new boolean[w * h];
    int[] q = new int[w * h];
    int head = 0, tail = 0;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        if (x != 0 && x != w - 1 && y != 0 && y != h - 1) continue;
        int i = y * w + x;
        if (!bg[i] && (alpha[i] < 128 || (r[i] > 240 && g[i] > 240 && b[i] > 240))) {
          bg[i] = true; q[tail++] = i;
        }
      }
    }
    int[] nx4 = {1, -1, 0, 0}, ny4 = {0, 0, 1, -1};
    while (head < tail) {
      int cur = q[head++], cx = cur % w, cy = cur / w;
      for (int d = 0; d < 4; d++) {
        int x = cx + nx4[d], y = cy + ny4[d];
        if (x < 0 || x >= w || y < 0 || y >= h) continue;
        int ni = y * w + x;
        if (!bg[ni] && (alpha[ni] < 128 || (r[ni] > 240 && g[ni] > 240 && b[ni] > 240))) {
          bg[ni] = true; q[tail++] = ni;
        }
      }
    }
    return bg;
  }

  private static ProcessResult processColorExact1Px(Bitmap bmp, int w, int h, int maxColors, boolean ignoreWhite) {
    int nPx = w * h;
    int[] pixels = new int[nPx];
    bmp.getPixels(pixels, 0, w, 0, 0, w, h);
    int[] r = new int[nPx], g = new int[nPx], b = new int[nPx], alpha = new int[nPx];
    for (int i = 0; i < nPx; i++) {
      int p = pixels[i];
      alpha[i] = Color.alpha(p); r[i] = Color.red(p); g[i] = Color.green(p); b[i] = Color.blue(p);
    }

    boolean[] outerBg = ignoreWhite ? detectOuterWhiteBg(r, g, b, alpha, w, h) : new boolean[nPx];
    boolean[] valid = new boolean[nPx];
    List<Integer> samples = new ArrayList<>(nPx);
    for (int i = 0; i < nPx; i++) {
      boolean skip = alpha[i] < 128 || outerBg[i];
      valid[i] = !skip;
      if (!skip) samples.add(i);
    }
    if (samples.isEmpty()) return new ProcessResult(renderPreview(new ArrayList<>(), w, h), new ArrayList<>(), 0, 0, w, h);

    int k = Math.min(Math.max(2, maxColors), samples.size());
    int[][] cent = seedCentroidsSmart(samples, r, g, b, k);

    for (int it = 0; it < 14; it++) {
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

    int[] label = new int[nPx];
    for (int i = 0; i < nPx; i++) label[i] = valid[i] ? nearestPerceptual(r[i], g[i], b[i], cent) : -1;
    label = cleanIsolatedDots(label, cent, w, h);

    List<ColorLayer> layers = new ArrayList<>();
    Map<ColorLayer, Integer> areaMap = new HashMap<>();
    int totValid = samples.size(), totSt = 0, totPts = 0;

    for (int c = 0; c < k; c++) {
      boolean[][] map = new boolean[h][w];
      int area = 0; long sr = 0, sg = 0, sb = 0;
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
          int idx = y * w + x;
          if (label[idx] == c) { map[y][x] = true; area++; sr += r[idx]; sg += g[idx]; sb += b[idx]; }
        }
      }
      if (area == 0) continue;
      int col = Color.rgb((int) (sr / area), (int) (sg / area), (int) (sb / area));
      boolean important = lum(col) < 70 || lum(col) > 235;
      int minArea = important ? 1 : Math.max(2, nPx / 12000);
      if (area < minArea) continue;

      ColorLayer layer = new ColorLayer(col);
      layer.strokes = scanTopDown1Px(map, w, h);
      int pts = 0;
      for (List<PointF> st : layer.strokes) pts += st.size();
      layer.pointCount = pts;
      if (!layer.strokes.isEmpty()) {
        layers.add(layer); areaMap.put(layer, area);
        totSt += layer.strokes.size(); totPts += pts;
      }
    }

    final int highlightLimit = Math.max(12, totValid / 12);
    Collections.sort(layers, (a, b2) -> {
      int aa = areaMap.get(a), ab = areaMap.get(b2);
      int ga = layerStage(a.color, aa, highlightLimit);
      int gb = layerStage(b2.color, ab, highlightLimit);
      if (ga != gb) return Integer.compare(ga, gb);
      return Integer.compare(ab, aa);
    });

    return new ProcessResult(renderPreview(layers, w, h), layers, totSt, totPts, w, h);
  }

  private static int layerStage(int col, int area, int highlightLimit) {
    int l = lum(col);
    if (l > 235 && area <= highlightLimit) return 2;
    if (l < 70) return 1;
    return 0;
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
        if (x0 == x) {
          line.add(new PointF((x0 + 0.45f) / w, py));
          line.add(new PointF((x0 + 0.55f) / w, py));
        } else {
          line.add(new PointF((x0 + 0.5f) / w, py));
          line.add(new PointF((x + 0.5f) / w, py));
        }
        strokes.add(line);
        x++;
      }
    }
    return strokes;
  }

  private static int[][] seedCentroidsSmart(List<Integer> samples, int[] r, int[] g, int[] b, int k) {
    int n = samples.size();
    int[][] cent = new int[k][3];
    int step = Math.max(1, n / 4500);
    List<Integer> sub = new ArrayList<>(Math.min(n, 5000));
    for (int i = 0; i < n; i += step) sub.add(samples.get(i));
    int m = sub.size();

    int placed = 0;
    int darkIdx = -1, minLum = 70, darkCount = 0;
    for (int idx : sub) {
      int l = (r[idx] * 299 + g[idx] * 587 + b[idx] * 114) / 1000;
      if (l < 70) { darkCount++; if (l < minLum) { minLum = l; darkIdx = idx; } }
    }
    if (darkIdx >= 0 && darkCount >= 2 && placed < k) {
      cent[placed][0] = r[darkIdx]; cent[placed][1] = g[darkIdx]; cent[placed][2] = b[darkIdx]; placed++;
    }

    int brightIdx = -1, maxLum = 235, brightCount = 0;
    for (int idx : sub) {
      int l = (r[idx] * 299 + g[idx] * 587 + b[idx] * 114) / 1000;
      if (l > 235) { brightCount++; if (l > maxLum) { maxLum = l; brightIdx = idx; } }
    }
    if (brightIdx >= 0 && brightCount >= 2 && placed < k) {
      cent[placed][0] = r[brightIdx]; cent[placed][1] = g[brightIdx]; cent[placed][2] = b[brightIdx]; placed++;
    }

    if (placed == 0) {
      int mid = sub.get(m / 2);
      cent[0][0] = r[mid]; cent[0][1] = g[mid]; cent[0][2] = b[mid]; placed = 1;
    }

    long[] minDist = new long[m];
    Arrays.fill(minDist, Long.MAX_VALUE);
    for (int c = 0; c < placed; c++) {
      for (int i = 0; i < m; i++) {
        int idx = sub.get(i);
        long d = distPerceptual(r[idx], g[idx], b[idx], cent[c][0], cent[c][1], cent[c][2]);
        if (d < minDist[i]) minDist[i] = d;
      }
    }
    while (placed < k) {
      int pick = 0; long bestScore = -1;
      for (int i = 0; i < m; i++) {
        int idx = sub.get(i);
        int sat = Math.max(r[idx], Math.max(g[idx], b[idx])) - Math.min(r[idx], Math.min(g[idx], b[idx]));
        long score = minDist[i] * (200L + sat);
        if (score > bestScore) { bestScore = score; pick = i; }
      }
      int pIdx = sub.get(pick);
      cent[placed][0] = r[pIdx]; cent[placed][1] = g[pIdx]; cent[placed][2] = b[pIdx];
      for (int i = 0; i < m; i++) {
        int idx = sub.get(i);
        long d = distPerceptual(r[idx], g[idx], b[idx], cent[placed][0], cent[placed][1], cent[placed][2]);
        if (d < minDist[i]) minDist[i] = d;
      }
      placed++;
    }
    return cent;
  }

  private static int[] cleanIsolatedDots(int[] label, int[][] cent, int w, int h) {
    int[] next = label.clone();
    for (int y = 1; y < h - 1; y++) {
      for (int x = 1; x < w - 1; x++) {
        int own = label[y * w + x];
        if (own < 0) continue;
        int ownL = (cent[own][0] * 299 + cent[own][1] * 587 + cent[own][2] * 114) / 1000;
        if (ownL < 70 || ownL > 235) continue;
        int best = own, bestC = 0;
        for (int dy = -1; dy <= 1; dy++) {
          for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) continue;
            int l = label[(y + dy) * w + (x + dx)];
            if (l < 0 || l == own) continue;
            int c = 0;
            for (int ey = -1; ey <= 1; ey++)
              for (int ex = -1; ex <= 1; ex++)
                if ((ex != 0 || ey != 0) && label[(y + ey) * w + (x + ex)] == l) c++;
            if (c > bestC) { bestC = c; best = l; }
          }
        }
        if (best != own && bestC >= 7) next[y * w + x] = best;
      }
    }
    return next;
  }

  private static ProcessResult processMono1Px(Bitmap bmp, int w, int h, int thresh, boolean edgeMode) {
    int[][] gray = new int[h][w];
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int p = bmp.getPixel(x, y);
        gray[y][x] = Color.alpha(p) < 128 ? 255 : lum(p);
      }
    List<List<PointF>> strokes;
    if (edgeMode) {
      strokes = traceLines(extractEdges(gray, w, h, thresh), w, h);
    } else {
      boolean[][] map = new boolean[h][w];
      for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) map[y][x] = gray[y][x] < thresh;
      strokes = scanTopDown1Px(map, w, h);
    }
    ColorLayer layer = new ColorLayer(Color.BLACK);
    layer.strokes = strokes;
    int pts = 0;
    for (List<PointF> st : strokes) pts += st.size();
    layer.pointCount = pts;
    List<ColorLayer> layers = strokes.isEmpty() ? new ArrayList<>() : Collections.singletonList(layer);
    return new ProcessResult(renderPreview(layers, w, h), layers, strokes.size(), pts, w, h);
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
      vis[cy][cx] = true; raw.add(new int[]{cx, cy});
      int bestD = -1; float bestS = -1e9f;
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

  private static long distPerceptual(int r1, int g1, int b1, int r2, int g2, int b2) {
    long rm = (r1 + r2) / 2L, dr = r1 - r2, dg = g1 - g2, db = b1 - b2;
    return (((512 + rm) * dr * dr) >> 8) + 4 * dg * dg + (((767 - rm) * db * db) >> 8);
  }

  private static int nearestPerceptual(int r, int g, int b, int[][] cent) {
    int best = 0; long minD = Long.MAX_VALUE;
    for (int i = 0; i < cent.length; i++) {
      long d = distPerceptual(r, g, b, cent[i][0], cent[i][1], cent[i][2]);
      if (d < minD) { minD = d; best = i; }
    }
    return best;
  }

  public static Bitmap renderPreview(List<ColorLayer> layers, int targetW, int targetH) {
    int scale = Math.max(2, Math.min(4, 640 / Math.max(1, Math.max(targetW, targetH))));
    int outW = Math.max(120, targetW * scale), outH = Math.max(120, targetH * scale);
    Bitmap preview = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(preview);
    canvas.drawColor(Color.WHITE);
    Paint paint = new Paint();
    paint.setAntiAlias(false);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.SQUARE);
    paint.setStrokeWidth(Math.max(1.5f, scale * 1.02f));
    for (ColorLayer layer : layers) {
      if (!layer.enabled) continue;
      paint.setColor(layer.color);
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
