package com.myautodraw;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.GestureDescription.*;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityEvent;
import android.widget.*;
import java.util.List;

public class AutoDrawService extends AccessibilityService {
  private static AutoDrawService inst;
  private WindowManager wm;
  private View panel, preview;
  private LayoutParams pPanel, pBox, pColor, pHsv, pBrush;
  private TextView txtStatus, txtCode;
  private LinearLayout manualBar, autoBar;
  private BoxView boxV, hsvV;
  private DotView colorDot, brushDot;
  private Button btnHsv;
  private boolean markersOn = true;
  private volatile boolean drawing = false;
  private int runId = 0, drawnCount = 0, totalStrokes = 0;
  private long lastUiMs = 0L;
  private Runnable onGo, onSkip;
  private int sw = 1080, sh = 2400;
  private final Handler ui = new Handler(Looper.getMainLooper());

  public static boolean isRunning() { return inst != null; }
  public static void ensureOverlaysVisible() { if (inst != null) inst.ui.post(() -> inst.restoreAll()); }
  public static void syncMode() { if (inst != null) inst.ui.post(() -> { if (!inst.drawing) { inst.markersOn = true; inst.updMode(); inst.applyVis(); } }); }

  public void restoreAll() {
    if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    try {
      if (panel == null) { initOverlays(); return; }
      panel.setVisibility(View.VISIBLE); markersOn = true; updMode(); applyVis(); setPT(false);
    } catch (Exception ignored) {}
  }

  @Override protected void onServiceConnected() { super.onServiceConnected(); inst = this; ui.post(this::initOverlays); }

  private void resetDefaultPositions() {
    DrawRepository.boxW = (int)(sw * 0.72f); DrawRepository.boxH = DrawRepository.boxW;
    DrawRepository.boxX = (sw - DrawRepository.boxW) / 2; DrawRepository.boxY = (int)(sh * 0.24f);
    DrawRepository.hsvW = (int)(sw * 0.52f); DrawRepository.hsvH = (int)(sh * 0.14f);
    DrawRepository.hsvX = (sw - DrawRepository.hsvW) / 2; DrawRepository.hsvY = (int)(sh * 0.42f);
    DrawRepository.colorBtnX = (int)(sw * 0.70f); DrawRepository.colorBtnY = (int)(sh * 0.84f);
    DrawRepository.brushBtnX = (int)(sw * 0.11f); DrawRepository.brushBtnY = (int)(sh * 0.84f);
    DrawRepository.hasSavedPositions = true;
  }

  private void initOverlays() {
    if (panel != null) return;
    try {
      wm = (WindowManager) getSystemService(WINDOW_SERVICE);
      DisplayMetrics dm = getResources().getDisplayMetrics();
      sw = dm.widthPixels; sh = dm.heightPixels;
      if (!DrawRepository.hasSavedPositions) resetDefaultPositions();
      int t = Build.VERSION.SDK_INT >= 26 ? LayoutParams.TYPE_APPLICATION_OVERLAY : LayoutParams.TYPE_PHONE;
      if (!Settings.canDrawOverlays(this) && Build.VERSION.SDK_INT >= 22) t = LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
      Context ctx = new ContextThemeWrapper(this, R.style.AppTheme);
      boxV = new BoxView(ctx, false);
      pBox = lp(DrawRepository.boxW, DrawRepository.boxH, DrawRepository.boxX, DrawRepository.boxY, t);
      try { wm.addView(boxV, pBox); } catch (Exception e) {
        if (Build.VERSION.SDK_INT >= 22) {
          t = LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
          pBox = lp(DrawRepository.boxW, DrawRepository.boxH, DrawRepository.boxX, DrawRepository.boxY, t);
          wm.addView(boxV, pBox);
        } else throw e;
      }
      hsvV = new BoxView(ctx, true); pHsv = lp(DrawRepository.hsvW, DrawRepository.hsvH, DrawRepository.hsvX, DrawRepository.hsvY, t); wm.addView(hsvV, pHsv);
      colorDot = new DotView(ctx, "Màu", 0xC8EC4899, true); pColor = lp(96, 96, DrawRepository.colorBtnX, DrawRepository.colorBtnY, t); wm.addView(colorDot, pColor);
      brushDot = new DotView(ctx, "Bút", 0xC82563EB, false); pBrush = lp(96, 96, DrawRepository.brushBtnX, DrawRepository.brushBtnY, t); wm.addView(brushDot, pBrush);
      panel = LayoutInflater.from(ctx).inflate(R.layout.overlay_panel, null);
      pPanel = lp(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 36, 90, t); wm.addView(panel, pPanel);

      TextView drag = panel.findViewById(R.id.btnDragHandle);
      Button bTog = panel.findViewById(R.id.btnToggleMarkers), bGo = panel.findViewById(R.id.btnStartDraw);
      Button bStop = panel.findViewById(R.id.btnStopDraw), bCont = panel.findViewById(R.id.btnContinueDraw), bSkip = panel.findViewById(R.id.btnSkipColor);
      Button bTest = panel.findViewById(R.id.btnTestAutoColor), bReset = panel.findViewById(R.id.btnResetMarkers);
      txtStatus = panel.findViewById(R.id.txtOverlayStatus); manualBar = panel.findViewById(R.id.layoutManualPickBar);
      autoBar = panel.findViewById(R.id.layoutAutoCalibBar);
      txtCode = panel.findViewById(R.id.txtPickColorCode); btnHsv = panel.findViewById(R.id.btnColorModeSwitch);
      preview = panel.findViewById(R.id.viewColorPreview);

      drag.setOnTouchListener(new View.OnTouchListener() {
        int ix, iy; float tx, ty;
        public boolean onTouch(View v, MotionEvent e) {
          if (e.getAction() == MotionEvent.ACTION_DOWN) { ix = pPanel.x; iy = pPanel.y; tx = e.getRawX(); ty = e.getRawY(); return true; }
          if (e.getAction() == MotionEvent.ACTION_MOVE) { pPanel.x = ix + (int)(e.getRawX() - tx); pPanel.y = iy + (int)(e.getRawY() - ty); wm.updateViewLayout(panel, pPanel); return true; }
          return false;
        }
      });
      updMode(); applyVis();
      bTog.setOnClickListener(v -> { if (!drawing) { markersOn = !markersOn; applyVis(); } });
      btnHsv.setOnClickListener(v -> { if (!drawing) { DrawRepository.setAutoHsvBuilda(!DrawRepository.isAutoHsvBuilda()); markersOn = true; updMode(); applyVis(); } });
      bReset.setOnClickListener(v -> {
        if (drawing) return;
        resetDefaultPositions();
        pBox.x = DrawRepository.boxX; pBox.y = DrawRepository.boxY; pBox.width = DrawRepository.boxW; pBox.height = DrawRepository.boxH;
        pHsv.x = DrawRepository.hsvX; pHsv.y = DrawRepository.hsvY; pHsv.width = DrawRepository.hsvW; pHsv.height = DrawRepository.hsvH;
        pColor.x = DrawRepository.colorBtnX; pColor.y = DrawRepository.colorBtnY;
        pBrush.x = DrawRepository.brushBtnX; pBrush.y = DrawRepository.brushBtnY;
        try { wm.updateViewLayout(boxV, pBox); wm.updateViewLayout(hsvV, pHsv); wm.updateViewLayout(colorDot, pColor); wm.updateViewLayout(brushDot, pBrush); } catch (Exception ignored) {}
        markersOn = true; applyVis();
      });
      bTest.setOnClickListener(v -> {
        if (drawing) return;
        List<ColorLayer> ls = DrawRepository.getEnabledLayers();
        ColorLayer testL = (ls != null && !ls.isEmpty()) ? ls.get(0) : new ColorLayer(Color.rgb(255, 32, 85));
        int rid = ++runId; drawing = true; setPT(true); updMode();
        txtStatus.setText("Đang test chọn màu " + testL.getHexString() + "...");
        selectAutoColor(rid, testL, () -> {
          drawing = false;
          ui.post(() -> { setPT(false); updMode(); txtStatus.setText("Test xong " + testL.getHexString() + "! Nếu đúng màu hãy bấm Vẽ."); });
        });
      });
      bGo.setOnClickListener(v -> {
        if (drawing) return;
        List<ColorLayer> ls = DrawRepository.getEnabledLayers();
        if (ls == null || ls.isEmpty()) { Toast.makeText(this, "Chưa có ảnh! Chọn ảnh trước.", Toast.LENGTH_SHORT).show(); return; }
        startDraw(ls);
      });
      bCont.setOnClickListener(v -> { Runnable r = onGo; if (r == null) return; onGo = null; onSkip = null; hidePick(); drawing = true; setPT(true); setKeepScreen(true); updMode(); txtStatus.setText("Đang vẽ tiếp..."); r.run(); });
      bSkip.setOnClickListener(v -> { Runnable s = onSkip; if (s == null) return; onGo = null; onSkip = null; hidePick(); drawing = true; setPT(true); setKeepScreen(true); updMode(); txtStatus.setText("Bỏ qua màu..."); s.run(); });
      bStop.setOnClickListener(v -> { runId++; drawing = false; onGo = null; onSkip = null; hidePick(); setPT(false); setKeepScreen(false); updMode(); applyVis(); txtStatus.setText("Đã dừng (" + drawnCount + "/" + totalStrokes + " nét)."); });
    } catch (Exception e) { clearViews(); Toast.makeText(this, "Lỗi menu nổi: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
  }

  private void clearViews() {
    for (View v : new View[]{panel, boxV, hsvV, colorDot, brushDot}) if (wm != null && v != null) try { wm.removeView(v); } catch (Exception ignored) {}
    panel = null; boxV = null; hsvV = null; colorDot = null; brushDot = null;
  }

  private void updMode() {
    if (btnHsv == null || txtStatus == null) return;
    boolean auto = DrawRepository.isAutoHsvBuilda();
    btnHsv.setText(auto ? "Màu:Tự động" : "Màu:Tay");
    if (autoBar != null) autoBar.setVisibility((auto && !drawing) ? View.VISIBLE : View.GONE);
    if (!drawing) txtStatus.setText(auto ? "Tự động: Đặt 3 vạch cam khớp H-S-V, chấm [Màu],[Bút] rồi bấm Vẽ." : "Thủ công: Đặt [Khung vẽ] lên giấy vẽ rồi bấm Vẽ.");
  }

  private void applyVis() {
    if (boxV == null) return;
    boxV.setVisibility(markersOn ? View.VISIBLE : View.GONE);
    int hv = (markersOn && DrawRepository.isAutoHsvBuilda()) ? View.VISIBLE : View.GONE;
    hsvV.setVisibility(hv); colorDot.setVisibility(hv); brushDot.setVisibility(hv);
  }

  private void hidePick() { if (manualBar != null) manualBar.setVisibility(View.GONE); }
  private LayoutParams lp(int w, int h, int x, int y, int t) { LayoutParams p = new LayoutParams(w, h, t, LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT); p.gravity = Gravity.TOP | Gravity.START; p.x = x; p.y = y; return p; }
  private void setPT(boolean pt) { uPT(boxV, pBox, pt, 0.18f); uPT(hsvV, pHsv, pt, 0f); uPT(colorDot, pColor, pt, 0.15f); uPT(brushDot, pBrush, pt, 0.15f); }
  private void uPT(View v, LayoutParams p, boolean pt, float a) { if (v == null || p == null) return; if (pt) { p.flags |= LayoutParams.FLAG_NOT_TOUCHABLE; v.setAlpha(a); } else { p.flags &= ~LayoutParams.FLAG_NOT_TOUCHABLE; v.setAlpha(1f); } try { wm.updateViewLayout(v, p); } catch (Exception ignored) {} }
  private void setKeepScreen(boolean on) { if (panel == null || pPanel == null) return; if (on) pPanel.flags |= LayoutParams.FLAG_KEEP_SCREEN_ON; else pPanel.flags &= ~LayoutParams.FLAG_KEEP_SCREEN_ON; try { wm.updateViewLayout(panel, pPanel); } catch (Exception ignored) {} }

  private void startDraw(List<ColorLayer> ls) {
    if (!markersOn) { markersOn = true; applyVis(); }
    float w = boxV.getWidth(), h = boxV.getHeight(), r = DrawRepository.getAspectRatio();
    if (w <= 0 || h <= 0) return;
    int sum = 0; for (ColorLayer cl : ls) if (cl.strokes != null) sum += cl.strokes.size();
    if (sum == 0) return;
    int rid = ++runId;
    drawnCount = 0; totalStrokes = sum; drawing = true; setPT(true); setKeepScreen(true); updMode();
    int[] loc = new int[2]; boxV.getLocationOnScreen(loc);
    float dw = w, dh = w / r; if (dh > h) { dh = h; dw = h * r; }
    runLayer(rid, ls, 0, loc[0] + (w - dw) / 2f, loc[1] + (h - dh) / 2f, dw, dh);
  }

  private void runLayer(int rid, List<ColorLayer> ls, int idx, float ox, float oy, float dw, float dh) {
    if (!drawing || rid != runId) return;
    if (idx >= ls.size()) {
      drawing = false;
      ui.post(() -> { hidePick(); setPT(false); setKeepScreen(false); updMode(); applyVis(); txtStatus.setText("Hoàn tất (" + drawnCount + "/" + totalStrokes + " nét)!"); Toast.makeText(this, "Đã vẽ xong!", Toast.LENGTH_SHORT).show(); });
      return;
    }
    ColorLayer l = ls.get(idx);
    Runnable go = () -> drawStroke(rid, ls, idx, 0, 0, ox, oy, dw, dh), sk = () -> runLayer(rid, ls, idx + 1, ox, oy, dw, dh);
    if (DrawRepository.isAutoHsvBuilda()) {
      ui.post(() -> txtStatus.setText("Màu " + (idx + 1) + "/" + ls.size() + ": " + l.getHexString() + " (H" + l.hue + " S" + l.saturation + " V" + l.value + ")"));
      selectAutoColor(rid, l, go);
    } else {
      onGo = go; onSkip = sk;
      String nl = String.valueOf((char) 10);
      ui.post(() -> { setPT(true); preview.setBackgroundColor(l.color); txtCode.setText(l.getHexString() + nl + "H:" + l.hue + " S:" + l.saturation + " V:" + l.value); manualBar.setVisibility(View.VISIBLE); txtStatus.setText("Chọn màu " + (idx + 1) + "/" + ls.size() + " (" + l.getHexString() + ") rồi bấm [Vẽ tiếp ▶]"); });
    }
  }

  // Chuỗi mở bảng màu chuẩn: Chạm [Bút] đóng bảng cũ (nếu đang mở) -> Chạm [Màu] mở bảng mới -> Kéo H, S, V -> Chạm [Bút] xác nhận
  private void selectAutoColor(int rid, ColorLayer l, Runnable done) {
    PointF b = ctr(brushDot), c = ctr(colorDot);
    tap(rid, b.x, b.y, () -> ui.postDelayed(() -> {
      if (!drawing || rid != runId) return;
      tap(rid, c.x, c.y, () -> ui.postDelayed(() -> {
        if (!drawing || rid != runId) return;
        applyHsv(rid, l, () -> ui.postDelayed(() -> {
          if (!drawing || rid != runId) return;
          PointF b2 = ctr(brushDot);
          tap(rid, b2.x, b2.y, () -> ui.postDelayed(done, 520L));
        }, 220L));
      }, 820L));
    }, 380L));
  }

  private void applyHsv(int rid, ColorLayer l, Runnable done) {
    int[] loc = new int[2]; hsvV.getLocationOnScreen(loc);
    float w = hsvV.getWidth(), h = hsvV.getHeight();
    float pad = Math.max(26f, w * 0.08f);
    float left = loc[0] + pad, tw = Math.max(40f, w - 2f * pad), top = loc[1];
    setSl(rid, left, tw, top + h / 6f, l.hue / 360f, () -> ui.postDelayed(() -> {
      if (!drawing || rid != runId) return;
      setSl(rid, left, tw, top + h * 0.5f, l.saturation / 100f, () -> ui.postDelayed(() -> {
        if (!drawing || rid != runId) return;
        setSl(rid, left, tw, top + h * (5f / 6f), l.value / 100f, done);
      }, 210L));
    }, 210L));
  }

  private void setSl(int rid, float left, float tw, float y, float f, Runnable done) {
    float clampedF = Math.max(0f, Math.min(1f, f));
    float tx = left + clampedF * tw;
    // 1. Kéo từ phải sang trái để thu núm trượt về đúng 0% bất kể vị trí cũ
    slideHold(rid, left + tw, left, y, 210L, () -> ui.postDelayed(() -> {
      if (!drawing || rid != runId) return;
      if (clampedF <= 0.01f) {
        tap(rid, left, y, done);
      } else {
        // 2. Nếu khoảng cách nhỏ (<28px), lướt nhẹ vượt ngưỡng touchSlop rồi quay lại tx để tránh trễ cảm ứng
        float midX = (tx - left < 28f) ? Math.min(left + tw, left + 28f) : tx;
        slideTriHold(rid, left, midX, tx, y, 250L, () -> ui.postDelayed(() -> {
          if (!drawing || rid != runId) return;
          // 3. Chạm xác nhận ngay tại tx cho thanh trượt kiểu chạm trực tiếp
          tap(rid, tx, y, done);
        }, 110L));
      }
    }, 130L));
  }

  private float cx(float x) { return Math.max(1f, Math.min(sw - 1f, x)); }
  private float cy(float y) { return Math.max(1f, Math.min(sh - 1f, y)); }

  private boolean gest(int rid, Path p, long d, Runnable okCb, Runnable canCb) {
    if (!drawing || rid != runId) return false;
    try { return dispatchGesture(new Builder().addStroke(new StrokeDescription(p, 0, d)).build(), new GestureResultCallback() { public void onCompleted(GestureDescription g) { if (drawing && rid == runId && okCb != null) okCb.run(); } public void onCancelled(GestureDescription g) { if (drawing && rid == runId && canCb != null) canCb.run(); } }, null); }
    catch (Exception e) { return false; }
  }

  private void slideHold(int rid, float fx, float tx, float y, long d, Runnable done) {
    Path p = new Path(); p.moveTo(cx(fx), cy(y)); p.lineTo(cx(tx), cy(y)); p.lineTo(cx(tx), cy(y));
    if (!gest(rid, p, d, done, () -> pauseOnBlock(null, null))) pauseOnBlock(null, null);
  }
  private void slideTriHold(int rid, float fx, float mx, float tx, float y, long d, Runnable done) {
    Path p = new Path(); p.moveTo(cx(fx), cy(y)); p.lineTo(cx(mx), cy(y)); p.lineTo(cx(tx), cy(y)); p.lineTo(cx(tx), cy(y));
    if (!gest(rid, p, d, done, () -> pauseOnBlock(null, null))) pauseOnBlock(null, null);
  }
  private void tap(int rid, float x, float y, Runnable done) {
    Path p = new Path(); p.moveTo(cx(x), cy(y)); p.lineTo(cx(x) + 1f, cy(y) + 1f);
    if (!gest(rid, p, 75L, done, () -> pauseOnBlock(null, null))) pauseOnBlock(null, null);
  }

  private void pauseOnBlock(Runnable resumeStroke, Runnable skipLayer) {
    drawing = false;
    ui.post(() -> {
      setPT(false); setKeepScreen(false); updMode();
      if (resumeStroke != null) {
        onGo = resumeStroke; onSkip = skipLayer;
        if (manualBar != null) manualBar.setVisibility(View.VISIBLE);
        if (txtStatus != null) txtStatus.setText("Tạm dừng (" + drawnCount + "/" + totalStrokes + " nét) — bấm [Vẽ tiếp ▶] để vẽ nốt.");
      } else {
        hidePick();
        if (txtStatus != null) txtStatus.setText("Lỗi chạm. Hãy tắt/bật lại Trợ năng.");
      }
    });
  }

  private PointF ctr(View v) { int[] loc = new int[2]; v.getLocationOnScreen(loc); return new PointF(loc[0] + v.getWidth() / 2f, loc[1] + v.getHeight() / 2f); }

  private void drawStroke(int rid, List<ColorLayer> ls, int li, int si, int rt, float ox, float oy, float dw, float dh) {
    if (!drawing || rid != runId) return;
    List<List<PointF>> sts = ls.get(li).strokes;
    if (si >= sts.size()) { ui.postDelayed(() -> runLayer(rid, ls, li + 1, ox, oy, dw, dh), 180L); return; }
    long now = SystemClock.uptimeMillis();
    if (now - lastUiMs > 450L) {
      lastUiMs = now;
      int tot = sts.size();
      ui.post(() -> { if (txtStatus != null && drawing) txtStatus.setText("Màu " + (li + 1) + "/" + ls.size() + " — nét " + (si + 1) + "/" + tot + " (" + drawnCount + "/" + totalStrokes + ")"); });
    }
    List<PointF> st = sts.get(si);
    if (st.isEmpty()) { ui.post(() -> drawStroke(rid, ls, li, si + 1, 0, ox, oy, dw, dh)); return; }
    Path path = new Path();
    float px = cx(ox + st.get(0).x * dw), py = cy(oy + st.get(0).y * dh), len = 0f;
    path.moveTo(px, py);
    for (int i = 1; i < st.size(); i++) {
      float nx = cx(ox + st.get(i).x * dw), ny = cy(oy + st.get(i).y * dh);
      len += (float) Math.hypot(nx - px, ny - py); path.lineTo(nx, ny); px = nx; py = ny;
    }
    if (len < 0.8f) { px = cx(px + 0.6f); path.lineTo(px, py); }
    path.lineTo(px, py);

    long base = DrawRepository.getStrokeDurationMs();
    long dur = (len < 2f) ? Math.max(40L, base + 10L) : (long) Math.max((float) (base + 12L), Math.min(12000f, base + 14L + len * (base / 48f)));
    long gap = Math.max(20L, base / 2L);
    Runnable retryOrPause = () -> {
      if (!drawing || rid != runId) return;
      if (rt < 16) {
        long wait = Math.min(450L, 65L + rt * 25L);
        ui.postDelayed(() -> drawStroke(rid, ls, li, si, rt + 1, ox, oy, dw, dh), wait);
      } else {
        pauseOnBlock(() -> drawStroke(rid, ls, li, si, 0, ox, oy, dw, dh), () -> runLayer(rid, ls, li + 1, ox, oy, dw, dh));
      }
    };
    boolean ok = gest(rid, path, dur, () -> {
      drawnCount++;
      ui.postDelayed(() -> drawStroke(rid, ls, li, si + 1, 0, ox, oy, dw, dh), gap);
    }, retryOrPause);
    if (!ok) retryOrPause.run();
  }

  private Paint mp(int col, float w, boolean s) { Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(col); if (s) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w); } else if (w > 0) { p.setTextSize(w); p.setFakeBoldText(true); } return p; }
  @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
  @Override public void onInterrupt() { runId++; drawing = false; }
  @Override public void onDestroy() { inst = null; runId++; super.onDestroy(); drawing = false; clearViews(); }

  private class BoxView extends View {
    final boolean isHsv; final Paint bp, cp, tp, lp; float lx, ly; boolean res = false;
    BoxView(Context c, boolean h) { super(c); isHsv = h; bp = mp(h ? 0xFFF59E0B : 0xFF38BDF8, h ? 4f : 5f, true); cp = mp(h ? 0xFFF59E0B : 0xFF10B981, 0f, false); tp = mp(h ? Color.WHITE : 0xFF38BDF8, h ? 22f : 28f, false); lp = mp(0xFFFBBF24, 4f, true); }
    protected void onDraw(Canvas c) {
      int w = getWidth(), h = getHeight(); c.drawColor(isHsv ? 0x2A1E293B : 0x1438BDF8); c.drawRect(2, 2, w - 2, h - 2, bp);
      if (isHsv) {
        float pad = Math.max(26f, w * 0.08f);
        String[] lb = {"H", "S", "V"};
        for (int i = 0; i < 3; i++) {
          float y = h * ((2 * i + 1) / 6f);
          c.drawLine(pad, y, w - pad, y, lp);
          c.drawLine(pad, y - 10f, pad, y + 10f, lp);
          c.drawLine(w - pad, y - 10f, w - pad, y + 10f, lp);
          c.drawText(lb[i], 6, y + 7, tp);
        }
        c.drawCircle(w - 18, h - 18, 18, cp);
      } else { c.drawText("KHUNG VẼ", 14, 36, tp); c.drawCircle(w - 22, h - 22, 22, cp); }
    }
    public boolean onTouchEvent(MotionEvent e) {
      if (drawing) return false; LayoutParams p = isHsv ? pHsv : pBox;
      if (e.getAction() == MotionEvent.ACTION_DOWN) { lx = e.getRawX(); ly = e.getRawY(); int m = isHsv ? 60 : 70; res = (e.getX() > getWidth() - m && e.getY() > getHeight() - m); return true; }
      if (e.getAction() == MotionEvent.ACTION_MOVE) {
        int dx = (int)(e.getRawX() - lx), dy = (int)(e.getRawY() - ly); lx = e.getRawX(); ly = e.getRawY();
        if (res) { p.width = Math.max(160, p.width + dx); p.height = Math.max(isHsv ? 110 : 160, p.height + dy); } else { p.x += dx; p.y += dy; }
        if (isHsv) { DrawRepository.hsvX = p.x; DrawRepository.hsvY = p.y; DrawRepository.hsvW = p.width; DrawRepository.hsvH = p.height; } else { DrawRepository.boxX = p.x; DrawRepository.boxY = p.y; DrawRepository.boxW = p.width; DrawRepository.boxH = p.height; }
        wm.updateViewLayout(this, p); return true;
      }
      return super.onTouchEvent(e);
    }
  }

  private class DotView extends View {
    final String label; final boolean isColor; final Paint fp, rp, tp; float lx, ly;
    DotView(Context c, String l, int col, boolean isC) { super(c); label = l; isColor = isC; fp = mp(col, 0f, false); rp = mp(Color.WHITE, 4f, true); tp = mp(Color.WHITE, 24f, false); tp.setTextAlign(Paint.Align.CENTER); }
    protected void onDraw(Canvas c) { float x = getWidth() / 2f, y = getHeight() / 2f, r = Math.min(x, y) - 4f; c.drawCircle(x, y, r, fp); c.drawCircle(x, y, r, rp); c.drawText(label, x, y + 8f, tp); }
    public boolean onTouchEvent(MotionEvent e) {
      if (drawing) return false; LayoutParams p = isColor ? pColor : pBrush;
      if (e.getAction() == MotionEvent.ACTION_DOWN) { lx = e.getRawX(); ly = e.getRawY(); return true; }
      if (e.getAction() == MotionEvent.ACTION_MOVE) {
        p.x += (int)(e.getRawX() - lx); p.y += (int)(e.getRawY() - ly); lx = e.getRawX(); ly = e.getRawY();
        if (isColor) { DrawRepository.colorBtnX = p.x; DrawRepository.colorBtnY = p.y; } else { DrawRepository.brushBtnX = p.x; DrawRepository.brushBtnY = p.y; }
        wm.updateViewLayout(this, p); return true;
      }
      return super.onTouchEvent(e);
    }
  }
}
