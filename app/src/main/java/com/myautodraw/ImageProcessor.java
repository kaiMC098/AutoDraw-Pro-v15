package com.myautodraw;

import android.graphics.*;
import java.util.*;

public class ImageProcessor {
  public static final int MODE_COLOR = 0, MODE_EDGE = 1, MODE_THRESHOLD = 2;
  private static final int MAX_EDGE_PTS = 20;
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
    int sw = Math.max(1, src.getWidth()), sh = Math.max(1, src.getHeight());
    int w, h;
    if (mode == MODE_COLOR) {
      // Cân bằng theo diện tích thay vì ép cạnh dài -> ảnh ngang 16:9 hoặc dọc vẫn cực sắc nét (tối đa 560px)
      double targetGeo = 140.0 + (thresh / 255.0) * 280.0;
      double ratio = (double) sw / sh;
      w = (int) Math.round(targetGeo * Math.sqrt(ratio));
      h = (int) Math.round(targetGeo / Math.sqrt(ratio));
      int maxCap = 560;
      if (Math.max(w, h) > maxCap) {
        double s = (double) maxCap / Math.max(w, h);
        w = (int) Math.round(w * s);
        h = (int) Math.round(h * s);
      }
      if (w > sw || h > sh) {
        double s = Math.min((double) sw / w, (double) sh / h);
        w = (int) Math.round(w * s);
        h = (int) Math.round(h * s);
      }
    } else {
      float sc = 320f / Math.max(sw, sh);
      w = Math.round(sw * sc);
      h = Math.round(sh * sc);
    }
    w = Math.max(24, w);
    h = Math.max(24, h);

    Bitmap scaledBmp = progressiveDownscale(src, w, h);
    Bitmap crispBmp = crispEnhanceNoBlur(scaledBmp, w, h);
    if (scaledBmp != src && scaledBmp != crispBmp) scaledBmp.recycle();

    ProcessResult res = (mode == MODE_COLOR)
        ? processColorUltraSharp(crispBmp, w, h, maxColors, ignoreWhite)
        : processMono(crispBmp, w, h, thresh, mode == MODE_EDGE);
    if (crispBmp != src) crispBmp.recycle();
    return res;
  }

  // Thu nhỏ nhiều nhịp (mỗi nhịp tối đa 2x) giúp giữ nguyên nét mắt, tóc mảnh và chữ nhỏ thay vì bị mờ nhòe
  private static Bitmap progressiveDownscale(Bitmap src, int targetW, int targetH) {
    int cw = src.getWidth(), ch = src.getHeight();
    if (cw == targetW && ch == targetH) return src;
    Bitmap cur = src;
    while (cw / 2 >= targetW && ch / 2 >= targetH) {
      cw = Math.max(targetW, cw / 2);
      ch = Math.max(targetH, ch / 2);
      Bitmap next = Bitmap.createScaledBitmap(cur, cw, ch, true);
      if (cur != src) cur.recycle();
      cur = next;
    }
    if (cw != targetW || ch != targetH) {
      Bitmap fin = Bitmap.createScaledBitmap(cur, targetW, targetH, true);
      if (cur != src) cur.recycle();
      cur = fin;
    }
    return cur;
  }

  // Làm sắc nét trực tiếp không làm mờ chi tiết mảnh 1px (mắt, lông mi, hình xăm, chữ)
  private static Bitmap crispEnhanceNoBlur(Bitmap src, int w, int h) {
    if (w < 6 || h < 6) return src;
    int n = w * h;
    int[] px = new int[n];
    src.getPixels(px, 0, w, 0, 0, w, h);

    double sumG = 0.0; int cnt = 0;
    for (int y = 1; y < h - 1; y++) {
      for (int x = 1; x < w - 1; x++) {
        int c0 = px[y * w + x], cx = px[y * w + x + 1], cy = px[(y + 1) * w + x];
        sumG += Math.hypot(lum(cx) - lum(c0), lum(cy) - lum(c0)); cnt++;
      }
    }
    double meanG = cnt > 0 ? sumG / cnt : 16.0;
    float strength = (float) (0.55 + 0.65 * Math.max(0.0, Math.min(1.0, (16.0 - meanG) / 12.0)));

    int[] out = new int[n];
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

        int nr = clamp(Math.round(sr), Math.max(0, minR - 4), Math.min(255, maxR + 4));
        int ng = clamp(Math.round(sg), Math.max(0, minG - 4), Math.min(255, maxG + 4));
        int nb = clamp(Math.round(sb), Math.max(0, minB - 4), Math.min(255, maxB + 4));
        out[idx] = Color.argb(a, nr, ng, nb);
      }
    }
    Bitmap sharp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    sharp.setPixels(out, 0, w, 0, 0, w, h);
    return sharp;
  }

  private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

  private static boolean isPureWhite(int r, int g, int b) {
    int maxC = Math.max(r, Math.max(g, b));
    int minC = Math.min(r, Math.min(g, b));
    return minC > 248 && (maxC - minC) <= 6;
  }

  private static boolean[] detectOuterPureWhiteBg(int[] r, int[] g, int[] b, int[] alpha, int w, int h) {
    boolean[] bg = new boolean[w * h];
    int[] q = new int[w * h];
    int head = 0, tail = 0;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        if (x != 0 && x != w - 1 && y != 0 && y != h - 1) continue;
        int i = y * w + x;
        if (!bg[i] && (alpha[i] < 128 || isPureWhite(r[i], g[i], b[i]))) {
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
        if (!bg[ni] && (alpha[ni] < 128 || isPureWhite(r[ni], g[ni], b[ni]))) {
          bg[ni] = true; q[tail++] = ni;
        }
      }
    }
    return bg;
  }

  private static ProcessResult processColorUltraSharp(Bitmap bmp, int w, int h, int maxColors, boolean ignoreWhite) {
    int nPx = w * h;
    int[] pixels = new int[nPx];
    bmp.getPixels(pixels, 0, w, 0, 0, w, h);
    int[] r = new int[nPx], g = new int[nPx], b = new int[nPx], alpha = new int[nPx];
    for (int i = 0; i < nPx; i++) {
      int p = pixels[i];
      alpha[i] = Color.alpha(p); r[i] = Color.red(p); g[i] = Color.green(p); b[i] = Color.blue(p);
    }

    boolean[] outerBg = ignoreWhite ? detectOuterPureWhiteBg(r, g, b, alpha, w, h) : new boolean[nPx];
    boolean[] valid = new boolean[nPx];
    int validCount = 0;
    for (int i = 0; i < nPx; i++) {
      boolean ok = alpha[i] >= 128 && !outerBg[i];
      valid[i] = ok;
      if (ok) validCount++;
    }
    if (validCount == 0) return new ProcessResult(renderPreview(new ArrayList<>(), w, h), new ArrayList<>(), 0, 0, w, h);

    int step = Math.max(1, validCount / 12000);
    List<Integer> trainSamples = new ArrayList<>(Math.min(validCount, 12500));
    int seen = 0;
    for (int i = 0; i < nPx; i++) {
      if (!valid[i]) continue;
      if (seen % step == 0) trainSamples.add(i);
      seen++;
    }

    int k = Math.min(Math.max(2, Math.min(64, maxColors)), trainSamples.size());
    int[][] cent = seedCentroidsSmart(trainSamples, r, g, b, k);

    for (int it = 0; it < 12; it++) {
      long[] sr = new long[k], sg = new long[k], sb = new long[k];
      int[] cnt = new int[k];
      for (int idx : trainSamples) {
        int best = nearestPerceptual(r[idx], g[idx], b[idx], cent);
        sr[best] += r[idx]; sg[best] += g[idx]; sb[best] += b[idx]; cnt[best]++;
      }
      boolean moved = false;
      for (int c = 0; c < k; c++) {
        if (cnt[c] > 0) {
          int nr = (int) (sr[c] / cnt[c]), ng = (int) (sg[c] / cnt[c]), nb = (int) (sb[c] / cnt[c]);
          if (Math.abs(nr - cent[c][0]) + Math.abs(ng - cent[c][1]) + Math.abs(nb - cent[c][2]) > 1) moved = true;
          cent[c][0] = nr; cent[c][1] = ng; cent[c][2] = nb;
        }
      }
      if (!moved) break;
    }

    // Bảng tra nhanh 5-bit (32x32x32 LUT): gán ảnh 560x310 x 64 màu siêu nhanh không lag máy
    int[] lut = new int[32768];
    Arrays.fill(lut, -1);
    int[] label = new int[nPx];
    for (int i = 0; i < nPx; i++) {
      if (!valid[i]) { label[i] = -1; continue; }
      int key = ((r[i] >> 3) << 10) | ((g[i] >> 3) << 5) | (b[i] >> 3);
      int best = lut[key];
      if (best < 0) {
        best = nearestPerceptual((r[i] & 0xF8) + 4, (g[i] & 0xF8) + 4, (b[i] & 0xF8) + 4, cent);
        lut[key] = best;
      }
      label[i] = best;
    }
    label = cleanOnlyIsolatedDots(label, cent, w, h);

    int[] areas = new int[k];
    int[] avgColors = new int[k];
    long[] sumR = new long[k], sumG = new long[k], sumB = new long[k];
    for (int i = 0; i < nPx; i++) {
      int c = label[i];
      if (c >= 0) { areas[c]++; sumR[c] += r[i]; sumG[c] += g[i]; sumB[c] += b[i]; }
    }

    List<Integer> activeClusters = new ArrayList<>();
    for (int c = 0; c < k; c++) {
      if (areas[c] <= 0) continue;
      avgColors[c] = Color.rgb((int) (sumR[c] / areas[c]), (int) (sumG[c] / areas[c]), (int) (sumB[c] / areas[c]));
      activeClusters.add(c);
    }

    final int highlightLimit = Math.max(16, validCount / 12);
    Collections.sort(activeClusters, (a, b2) -> {
      int ga = layerStage(avgColors[a], areas[a], highlightLimit);
      int gb = layerStage(avgColors[b2], areas[b2], highlightLimit);
      if (ga != gb) return Integer.compare(ga, gb);
      return Integer.compare(areas[b2], areas[a]);
    });

    int[] clusterRank = new int[k];
    Arrays.fill(clusterRank, -1);
    for (int rank = 0; rank < activeClusters.size(); rank++) clusterRank[activeClusters.get(rank)] = rank;
    int[] rankMap = new int[nPx];
    for (int i = 0; i < nPx; i++) rankMap[i] = (label[i] >= 0) ? clusterRank[label[i]] : -1;

    List<ColorLayer> layers = new ArrayList<>();
    int totSt = 0, totPts = 0;
    int numLayers = activeClusters.size();

    for (int rank = 0; rank < numLayers; rank++) {
      int c = activeClusters.get(rank);
      int col = avgColors[c];
      int l = lum(col);
      boolean isHighlight = l > 235 && areas[c] <= highlightLimit;

      boolean[][] exactMap = new boolean[h][w];
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
          if (label[y * w + x] == c) exactMap[y][x] = true;
        }
      }

      boolean[][] drawMap = exactMap;
      if (rank < numLayers - 1 && !isHighlight) {
        drawMap = new boolean[h][w];
        for (int y = 0; y < h; y++) {
          for (int x = 0; x < w; x++) {
            if (exactMap[y][x]) { drawMap[y][x] = true; continue; }
            if (rankMap[y * w + x] <= rank) continue;
            boolean touch = (x > 0 && exactMap[y][x - 1]) || (x + 1 < w && exactMap[y][x + 1])
                || (y > 0 && exactMap[y - 1][x]) || (y + 1 < h && exactMap[y + 1][x]);
            if (touch) drawMap[y][x] = true;
          }
        }
      }

      float bleed = isHighlight ? 0.25f : 0.42f;
      List<List<PointF>> strokes = scanStraightExact1Px(drawMap, w, h, bleed);

      if (!strokes.isEmpty()) {
        ColorLayer layer = new ColorLayer(col);
        layer.strokes = strokes;
        int pts = 0;
        for (List<PointF> st : strokes) pts += st.size();
        layer.pointCount = pts;
        layers.add(layer);
        totSt += strokes.size(); totPts += pts;
      }
    }

    return new ProcessResult(renderPreview(layers, w, h), layers, totSt, totPts, w, h);
  }

  private static int layerStage(int col, int area, int highlightLimit) {
    int l = lum(col);
    if (l > 235 && area <= highlightLimit) return 2;
    if (l < 62) return 1;
    return 0;
  }

  private static List<List<PointF>> scanStraightExact1Px(boolean[][] map, int w, int h, float bleed) {
    List<List<PointF>> strokes = new ArrayList<>();
    for (int y = 0; y < h; y++) {
      float py = (y + 0.5f) / h;
      List<int[]> rowRuns = new ArrayList<>();
      int x = 0;
      while (x < w) {
        if (!map[y][x]) { x++; continue; }
        int x0 = x;
        while (x + 1 < w && map[y][x + 1]) x++;
        rowRuns.add(new int[]{x0, x});
        x++;
      }
      boolean rev = (y % 2 == 1);
      int n = rowRuns.size();
      for (int idx = 0; idx < n; idx++) {
        int[] run = rowRuns.get(rev ? (n - 1 - idx) : idx);
        int x0 = run[0], x1 = run[1];
        float fx0 = Math.max(0.001f, (x0 + 0.5f - bleed) / w);
        float fx1 = Math.min(0.999f, (x1 + 0.5f + bleed) / w);
        List<PointF> line = new ArrayList<>(2);
        if (rev) {
          line.add(new PointF(fx1, py));
          line.add(new PointF(fx0, py));
        } else {
          line.add(new PointF(fx0, py));
          line.add(new PointF(fx1, py));
        }
        strokes.add(line);
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
    int darkIdx = -1, minLum = 65, darkCount = 0;
    for (int idx : sub) {
      int l = (r[idx] * 299 + g[idx] * 587 + b[idx] * 114) / 1000;
      if (l < 65) { darkCount++; if (l < minLum) { minLum = l; darkIdx = idx; } }
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
        long score = minDist[i] * (220L + sat);
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

  // Chỉ xóa điểm nhiễu đơn lẻ hoàn toàn (7/8 hàng xóm cùng màu khác) -> giữ trọn nét mảnh, chữ nhỏ & mắt
  private static int[] cleanOnlyIsolatedDots(int[] label, int[][] cent, int w, int h) {
    int[] next = label.clone();
    for (int y = 1; y < h - 1; y++) {
      for (int x = 1; x < w - 1; x++) {
        int own = label[y * w + x];
        if (own < 0) continue;
        int ownL = (cent[own][0] * 299 + cent[own][1] * 587 + cent[own][2] * 114) / 1000;
        if (ownL < 65 || ownL > 235) continue;
        int firstOther = -1, sameOther = 0;
        for (int dy = -1; dy <= 1; dy++) {
          for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) continue;
            int l = label[(y + dy) * w + (x + dx)];
            if (l < 0 || l == own) continue;
            if (firstOther < 0) { firstOther = l; sameOther = 1; }
            else if (l == firstOther) sameOther++;
          }
        }
        if (firstOther >= 0 && sameOther >= 7) next[y * w + x] = firstOther;
      }
    }
    return next;
  }

  private static ProcessResult processMono(Bitmap bmp, int w, int h, int thresh, boolean edgeMode) {
    int[][] gray = new int[h][w];
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int p = bmp.getPixel(x, y);
        gray[y][x] = Color.alpha(p) < 128 ? 255 : lum(p);
      }
    List<List<PointF>> strokes;
    if (edgeMode) {
      strokes = traceSmoothContours(extractEdges(gray, w, h, thresh), w, h);
    } else {
      boolean[][] map = new boolean[h][w];
      for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) map[y][x] = gray[y][x] < thresh;
      strokes = scanStraightExact1Px(map, w, h, 0.42f);
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

  private static List<List<PointF>> traceSmoothContours(boolean[][] map, int w, int h) {
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
          List<PointF> s = walkSmooth3Pass(map, vis, x, y, w, h);
          if (s.size() < 2) continue;
          s = simplifyRDP(s, 0.35f / Math.max(w, h));
          for (int i = 0; i < s.size() - 1; i += MAX_EDGE_PTS - 1)
            out.add(new ArrayList<>(s.subList(i, Math.min(s.size(), i + MAX_EDGE_PTS))));
        }
      }
    }
    return out;
  }

  private static List<PointF> walkSmooth3Pass(boolean[][] map, boolean[][] vis, int sx, int sy, int w, int h) {
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
    for (int pass = 0; pass < 3; pass++) {
      float[] nx = xs.clone(), ny = ys.clone();
      for (int i = 1; i < n - 1; i++) {
        nx[i] = 0.25f * xs[i - 1] + 0.5f * xs[i] + 0.25f * xs[i + 1];
        ny[i] = 0.25f * ys[i - 1] + 0.5f * ys[i] + 0.25f * ys[i + 1];
      }
      xs = nx; ys = ny;
    }
    List<PointF> pts = new ArrayList<>(n);
    for (int i = 0; i < n; i++) pts.add(new PointF(xs[i] / w, ys[i] / h));
    return pts;
  }

  private static List<PointF> simplifyRDP(List<PointF> pts, float eps) {
    if (pts.size() <= 3) return pts;
    int idx = 0; float maxD = 0f;
    PointF a = pts.get(0), b = pts.get(pts.size() - 1);
    float dx = b.x - a.x, dy = b.y - a.y, denom = (float) Math.hypot(dx, dy);
    for (int i = 1; i < pts.size() - 1; i++) {
      PointF p = pts.get(i);
      float d = denom < 1e-6f ? (float) Math.hypot(p.x - a.x, p.y - a.y) : Math.abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / denom;
      if (d > maxD) { maxD = d; idx = i; }
    }
    if (maxD > eps) {
      List<PointF> left = simplifyRDP(pts.subList(0, idx + 1), eps);
      List<PointF> right = simplifyRDP(pts.subList(idx, pts.size()), eps);
      List<PointF> res = new ArrayList<>(left.size() + right.size() - 1);
      res.addAll(left.subList(0, left.size() - 1));
      res.addAll(right);
      return res;
    }
    List<PointF> end = new ArrayList<>(2);
    end.add(a); end.add(b);
    return end;
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

  // Dựng ảnh xem trước sắc nét độ phân giải cao (lên tới ~1120px) trên nền caro nhạt
  public static Bitmap renderPreview(List<ColorLayer> layers, int targetW, int targetH) {
    int maxSide = Math.max(1, Math.max(targetW, targetH));
    int scale = Math.max(2, Math.min(4, 1120 / maxSide));
    int outW = Math.max(120, targetW * scale), outH = Math.max(120, targetH * scale);
    Bitmap preview = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(preview);
    canvas.drawColor(0xFFF1F5F9);
    Paint bgP = new Paint();
    bgP.setColor(0xFFE2E8F0);
    int cell = Math.max(14, Math.min(outW, outH) / 22);
    for (int y = 0; y < outH; y += cell) {
      for (int x = ((y / cell) % 2) * cell; x < outW; x += cell * 2) {
        canvas.drawRect(x, y, Math.min(outW, x + cell), Math.min(outH, y + cell), bgP);
      }
    }

    Paint paint = new Paint();
    paint.setAntiAlias(false);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.SQUARE);
    paint.setStrokeWidth(Math.max(1.5f, scale * 1.02f));
    for (ColorLayer layer : layers) {
      if (!layer.enabled) continue;
      paint.setColor(layer.color);
      for (List<PointF> st : layer.strokes) {
        if (st.size() < 2) continue;
        Path p = new Path();
        p.moveTo(st.get(0).x * outW, st.get(0).y * outH);
        for (int i = 1; i < st.size(); i++) p.lineTo(st.get(i).x * outW, st.get(i).y * outH);
        canvas.drawPath(p, paint);
      }
    }
    return preview;
  }
}
