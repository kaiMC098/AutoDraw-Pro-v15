package com.myautodraw;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class AutoDrawService extends AccessibilityService {
    private WindowManager windowManager;
    private View panelView;
    private WindowManager.LayoutParams panelParams;
    private TextView txtOverlayStatus;
    private TargetBoxView targetBoxView;
    private WindowManager.LayoutParams boxParams;
    private MarkerDotView colorBtnMarker;
    private WindowManager.LayoutParams colorBtnParams;
    private HsvSlidersBoxView hsvBoxView;
    private WindowManager.LayoutParams hsvBoxParams;
    private MarkerDotView brushBtnMarker;
    private WindowManager.LayoutParams brushBtnParams;
    private boolean markersVisible = true;
    private volatile boolean isDrawing = false;
    private Button btnHsv, btnContinue;
    private View viewColorPreview;
    private Runnable resumeAction;          // việc sẽ làm tiếp sau khi người dùng chọn màu xong
    private float curH = 0f, curS = 0f, curV = 0f;   // vị trí núm trượt (0..1) mà dịch vụ đang nhớ
    private int screenW = 1080, screenH = 2400;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (!Settings.canDrawOverlays(this)) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        targetBoxView = new TargetBoxView(this);
        boxParams = createParams(480, 480, 120, 220, type);
        windowManager.addView(targetBoxView, boxParams);

        hsvBoxView = new HsvSlidersBoxView(this);
        hsvBoxParams = createParams(360, 210, 230, 620, type);
        windowManager.addView(hsvBoxView, hsvBoxParams);

        colorBtnMarker = new MarkerDotView(this, "Màu", Color.parseColor("#EC4899"));
        colorBtnParams = createParams(96, 96, 680, 1180, type);
        windowManager.addView(colorBtnMarker, colorBtnParams);

        brushBtnMarker = new MarkerDotView(this, "Bút", Color.parseColor("#2563EB"));
        brushBtnParams = createParams(96, 96, 130, 1180, type);
        windowManager.addView(brushBtnMarker, brushBtnParams);

        panelView = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null);
        panelParams = createParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, 40, 90, type);
        windowManager.addView(panelView, panelParams);

        TextView drag = panelView.findViewById(R.id.btnDragHandle);
        Button btnToggle = panelView.findViewById(R.id.btnToggleMarkers);
        Button btnStart = panelView.findViewById(R.id.btnStartDraw);
        Button btnStop = panelView.findViewById(R.id.btnStopDraw);
        txtOverlayStatus = panelView.findViewById(R.id.txtOverlayStatus);

        drag.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy; float tx, ty;
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { ix = panelParams.x; iy = panelParams.y; tx = e.getRawX(); ty = e.getRawY(); return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) { panelParams.x = ix + (int)(e.getRawX()-tx); panelParams.y = iy + (int)(e.getRawY()-ty); windowManager.updateViewLayout(panelView, panelParams); return true; }
                return false;
            }
        });

        btnHsv = panelView.findViewById(R.id.btnColorModeSwitch);
        btnContinue = panelView.findViewById(R.id.btnContinueDraw);
        viewColorPreview = panelView.findViewById(R.id.viewColorPreview);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels; screenH = dm.heightPixels;

        btnHsv.setText(DrawRepository.isAutoHsvBuilda() ? "Màu:Tự động" : "Màu:Tay");
        applyMarkerVisibility();

        btnToggle.setOnClickListener(v -> {
            if (isDrawing) return;
            markersVisible = !markersVisible;
            applyMarkerVisibility();
        });

        btnHsv.setOnClickListener(v -> {
            if (isDrawing) return;
            boolean next = !DrawRepository.isAutoHsvBuilda();
            DrawRepository.setAutoHsvBuilda(next);
            if (next) { curH = 0f; curS = 0f; curV = 0f; }
            btnHsv.setText(next ? "Màu:Tự động" : "Màu:Tay");
            applyMarkerVisibility();
            txtOverlayStatus.setText(next
                ? "Tự động: đặt khung cam khít 3 thanh H-S-V của Builda (núm đang ở 0), chấm Màu/Bút lên nút tương ứng."
                : "Tay: mỗi màu sẽ dừng để bạn chọn, xong bấm Tiếp.");
        });

        btnStart.setOnClickListener(v -> {
            if (isDrawing) return;
            List<ColorLayer> layers = DrawRepository.getEnabledLayers();
            if (layers == null || layers.isEmpty()) { Toast.makeText(this, "Chưa có ảnh!", Toast.LENGTH_SHORT).show(); return; }
            startColorDrawing(layers);
        });

        btnContinue.setOnClickListener(v -> {
            Runnable r = resumeAction;
            if (r == null) return;
            resumeAction = null;
            hidePickUi();
            txtOverlayStatus.setText("Đang vẽ...");
            r.run();
        });

        btnStop.setOnClickListener(v -> {
            isDrawing = false;
            resumeAction = null;
            hidePickUi();
            setPassThrough(false);
            txtOverlayStatus.setText("Đã dừng.");
        });
    }

    private void applyMarkerVisibility() {
        int vis = markersVisible ? View.VISIBLE : View.GONE;
        targetBoxView.setVisibility(vis);
        int hsvVis = (markersVisible && DrawRepository.isAutoHsvBuilda()) ? View.VISIBLE : View.GONE;
        hsvBoxView.setVisibility(hsvVis);
        colorBtnMarker.setVisibility(hsvVis);
        brushBtnMarker.setVisibility(hsvVis);
    }

    private void hidePickUi() {
        if (btnContinue != null) btnContinue.setVisibility(View.GONE);
        if (viewColorPreview != null) viewColorPreview.setVisibility(View.GONE);
    }

    private WindowManager.LayoutParams createParams(int w, int h, int x, int y, int type) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START; p.x = x; p.y = y;
        return p;
    }

    private void setPassThrough(boolean pt) {
        updatePt(targetBoxView, boxParams, pt, 0.2f);
        updatePt(hsvBoxView, hsvBoxParams, pt, 0.12f);
        updatePt(colorBtnMarker, colorBtnParams, pt, 0.15f);
        updatePt(brushBtnMarker, brushBtnParams, pt, 0.15f);
    }

    private void updatePt(View v, WindowManager.LayoutParams p, boolean pt, float alpha) {
        if (v == null || p == null) return;
        if (pt) { p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; v.setAlpha(alpha); }
        else { p.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; v.setAlpha(1.0f); }
        try { windowManager.updateViewLayout(v, p); } catch (Exception ignored) {}
    }

    private void startColorDrawing(List<ColorLayer> layers) {
        if (!markersVisible) {
            Toast.makeText(this, "Hãy bấm [Khung] để hiện khung vẽ trước!", Toast.LENGTH_SHORT).show();
            return;
        }
        float w = targetBoxView.getWidth(), h = targetBoxView.getHeight(), ratio = DrawRepository.getAspectRatio();
        if (w <= 0 || h <= 0) return;
        isDrawing = true;
        setPassThrough(true);
        int[] loc = new int[2];
        targetBoxView.getLocationOnScreen(loc);
        float drawW = w, drawH = w / ratio;
        if (drawH > h) { drawH = h; drawW = h * ratio; }
        processLayer(layers, 0, loc[0] + (w - drawW) / 2f, loc[1] + (h - drawH) / 2f, drawW, drawH);
    }

    private void processLayer(List<ColorLayer> layers, int idx, float ox, float oy, float dw, float dh) {
        if (!isDrawing || idx >= layers.size()) {
            isDrawing = false;
            mainHandler.post(() -> { hidePickUi(); setPassThrough(false); txtOverlayStatus.setText("Hoàn tất!"); });
            return;
        }
        ColorLayer layer = layers.get(idx);
        Runnable startStrokes = () -> drawStroke(layers, idx, 0, ox, oy, dw, dh);
        if (DrawRepository.isAutoHsvBuilda()) {
            mainHandler.post(() -> txtOverlayStatus.setText("Màu " + (idx + 1) + "/" + layers.size() + ": đang chọn màu " + layer.getHexString()));
            autoSelectColor(layer, startStrokes);
        } else {
            manualSelectColor(layer, idx, layers.size(), startStrokes);
        }
    }

    /** Chế độ Tay: dừng lại, hiện màu cần chọn, đợi người dùng chọn trong Builda rồi bấm Tiếp. */
    private void manualSelectColor(ColorLayer layer, int idx, int total, Runnable done) {
        resumeAction = done;
        mainHandler.post(() -> {
            setPassThrough(true);
            viewColorPreview.setBackgroundColor(layer.color);
            viewColorPreview.setVisibility(View.VISIBLE);
            btnContinue.setVisibility(View.VISIBLE);
            txtOverlayStatus.setText("Màu " + (idx + 1) + "/" + total + ": chọn " + layer.getHexString()
                    + " (H" + layer.hue + " S" + layer.saturation + " V" + layer.value
                    + ") trong Builda, đóng bảng màu rồi bấm Tiếp.");
        });
    }

    /** Chế độ Tự động: mở bảng màu, kéo H-S-V tới đúng giá trị rồi bấm Bút để đóng bảng. */
    private void autoSelectColor(ColorLayer layer, Runnable done) {
        PointF cBtn = centerOf(colorBtnMarker);
        tap(cBtn.x, cBtn.y, () -> mainHandler.postDelayed(() -> {
            if (!isDrawing) return;
            applyHsv(layer, () -> {
                if (!isDrawing) return;
                PointF bBtn = centerOf(brushBtnMarker);
                tap(bBtn.x, bBtn.y, () -> mainHandler.postDelayed(done, 350L));
            });
        }, 450L));
    }

    private void applyHsv(ColorLayer layer, Runnable done) {
        int[] loc = new int[2];
        hsvBoxView.getLocationOnScreen(loc);
        final float left = loc[0] + 24f, trackW = Math.max(40f, hsvBoxView.getWidth() - 48f);
        final float top = loc[1], h = hsvBoxView.getHeight();
        final float yH = top + h * (1f / 6f), yS = top + h * (3f / 6f), yV = top + h * (5f / 6f);
        final float tH = layer.hue / 360f, tS = layer.saturation / 100f, tV = layer.value / 100f;

        mainHandler.postDelayed(() -> {
            if (!isDrawing) return;
            moveThumb(left, trackW, yH, curH, tH, () -> {
                curH = tH;
                mainHandler.postDelayed(() -> {
                    if (!isDrawing) return;
                    moveThumb(left, trackW, yS, curS, tS, () -> {
                        curS = tS;
                        mainHandler.postDelayed(() -> {
                            if (!isDrawing) return;
                            moveThumb(left, trackW, yV, curV, tV, () -> { curV = tV; done.run(); });
                        }, 180L);
                    });
                }, 180L);
            });
        }, 300L);
    }

    /** Kéo núm trượt từ vị trí đang nhớ tới vị trí mới (phải bắt đầu đúng chỗ núm đang đứng). */
    private void moveThumb(float left, float trackW, float y, float fromFrac, float toFrac, Runnable done) {
        float fx = left + fromFrac * trackW, tx = left + toFrac * trackW;
        if (Math.abs(tx - fx) < 2f) { done.run(); return; }
        slide(fx, tx, y, done);
    }

    private float clampX(float x) { return Math.max(1f, Math.min(screenW - 1f, x)); }
    private float clampY(float y) { return Math.max(1f, Math.min(screenH - 1f, y)); }

    private void slide(float fx, float tx, float y, Runnable done) {
        Path p = new Path();
        p.moveTo(clampX(fx), clampY(y)); p.lineTo(clampX(tx), clampY(y));
        long dur = 200L + (long) (Math.abs(tx - fx) * 0.6f);
        boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, dur)).build(),
            new GestureResultCallback() {
                public void onCompleted(GestureDescription g) { if (done != null) done.run(); }
                public void onCancelled(GestureDescription g) { isDrawing = false; mainHandler.post(() -> setPassThrough(false)); }
            }, null);
        if (!ok) failGesture();
    }

    private void tap(float x, float y, Runnable done) {
        Path p = new Path();
        p.moveTo(clampX(x), clampY(y)); p.lineTo(clampX(x) + 1f, clampY(y) + 1f);
        boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 60L)).build(),
            new GestureResultCallback() {
                public void onCompleted(GestureDescription g) { if (done != null) done.run(); }
                public void onCancelled(GestureDescription g) { isDrawing = false; mainHandler.post(() -> setPassThrough(false)); }
            }, null);
        if (!ok) failGesture();
    }

    private void failGesture() {
        isDrawing = false;
        mainHandler.post(() -> { hidePickUi(); setPassThrough(false); txtOverlayStatus.setText("Không gửi được thao tác chạm. Kiểm tra lại quyền Trợ năng."); });
    }

    private PointF centerOf(View v) {
        int[] loc = new int[2]; v.getLocationOnScreen(loc);
        return new PointF(loc[0] + v.getWidth() / 2f, loc[1] + v.getHeight() / 2f);
    }

    private void drawStroke(List<ColorLayer> layers, int lIdx, int sIdx, float ox, float oy, float dw, float dh) {
        if (!isDrawing) { mainHandler.post(() -> setPassThrough(false)); return; }
        List<List<PointF>> strokes = layers.get(lIdx).strokes;
        if (sIdx >= strokes.size()) {
            mainHandler.postDelayed(() -> processLayer(layers, lIdx + 1, ox, oy, dw, dh), 150L);
            return;
        }
        if (sIdx % 12 == 0) {
            final int total = strokes.size();
            mainHandler.post(() -> txtOverlayStatus.setText("Đang vẽ màu " + (lIdx + 1) + "/" + layers.size() + " - nét " + (sIdx + 1) + "/" + total));
        }
        List<PointF> st = strokes.get(sIdx);
        if (st.isEmpty()) { mainHandler.post(() -> drawStroke(layers, lIdx, sIdx + 1, ox, oy, dw, dh)); return; }

        Path path = new Path();
        PointF f = st.get(0);
        float px = clampX(ox + f.x * dw), py = clampY(oy + f.y * dh), length = 0f;
        path.moveTo(px, py);
        for (int i = 1; i < st.size(); i++) {
            float nx = clampX(ox + st.get(i).x * dw), ny = clampY(oy + st.get(i).y * dh);
            length += (float) Math.hypot(nx - px, ny - py);
            path.lineTo(nx, ny);
            px = nx; py = ny;
        }
        if (length < 0.5f) path.lineTo(px + 0.5f, py + 0.5f);

        // Thời gian tỉ lệ với độ dài nét để tay "vẽ" đều, không bị cắt góc.
        long base = DrawRepository.getStrokeDurationMs();
        long dur = (long) Math.max((float) base, Math.min(30000f, length * base / 40f));
        boolean ok;
        try {
            ok = dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, dur)).build(),
                new GestureResultCallback() {
                    public void onCompleted(GestureDescription g) { mainHandler.postDelayed(() -> drawStroke(layers, lIdx, sIdx + 1, ox, oy, dw, dh), 8L); }
                    public void onCancelled(GestureDescription g) { isDrawing = false; mainHandler.post(() -> setPassThrough(false)); }
                }, null);
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) mainHandler.post(() -> drawStroke(layers, lIdx, sIdx + 1, ox, oy, dw, dh));  // bỏ qua nét lỗi
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() { isDrawing = false; }
    @Override public void onDestroy() {
        super.onDestroy(); isDrawing = false;
        for (View v : new View[]{panelView, targetBoxView, hsvBoxView, colorBtnMarker, brushBtnMarker})
            if (windowManager != null && v != null) try { windowManager.removeView(v); } catch (Exception ignored) {}
    }

    private class TargetBoxView extends View {
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG), cp = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        float lx, ly; boolean res = false;
        TargetBoxView(Context c) {
            super(c);
            bp.setColor(Color.parseColor("#38BDF8")); bp.setStyle(Paint.Style.STROKE); bp.setStrokeWidth(5f);
            cp.setColor(Color.parseColor("#10B981")); tp.setColor(Color.parseColor("#38BDF8")); tp.setTextSize(28f); tp.setFakeBoldText(true);
        }
        protected void onDraw(Canvas c) {
            c.drawColor(Color.parseColor("#1438BDF8")); c.drawRect(2, 2, getWidth()-2, getHeight()-2, bp);
            c.drawText("KHUNG VẼ", 14, 36, tp); c.drawCircle(getWidth()-22, getHeight()-22, 22, cp);
        }
        public boolean onTouchEvent(MotionEvent e) {
            if (isDrawing) return false;
            if (e.getAction() == MotionEvent.ACTION_DOWN) { lx = e.getRawX(); ly = e.getRawY(); res = (e.getX() > getWidth()-70 && e.getY() > getHeight()-70); return true; }
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                int dx = (int)(e.getRawX()-lx), dy = (int)(e.getRawY()-ly); lx = e.getRawX(); ly = e.getRawY();
                if (res) { boxParams.width = Math.max(160, boxParams.width+dx); boxParams.height = Math.max(160, boxParams.height+dy); }
                else { boxParams.x += dx; boxParams.y += dy; }
                windowManager.updateViewLayout(this, boxParams); return true;
            }
            return super.onTouchEvent(e);
        }
    }

    private class HsvSlidersBoxView extends View {
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG), lp = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG), cp = new Paint(Paint.ANTI_ALIAS_FLAG);
        float lx, ly; boolean res = false;
        HsvSlidersBoxView(Context c) {
            super(c);
            bp.setColor(Color.parseColor("#F59E0B")); bp.setStyle(Paint.Style.STROKE); bp.setStrokeWidth(4f);
            lp.setColor(Color.parseColor("#FBBF24")); lp.setStrokeWidth(3f);
            tp.setColor(Color.WHITE); tp.setTextSize(24f); tp.setFakeBoldText(true); cp.setColor(Color.parseColor("#F59E0B"));
        }
        protected void onDraw(Canvas c) {
            c.drawColor(Color.parseColor("#26F59E0B")); int w = getWidth(), h = getHeight(); c.drawRect(2, 2, w-2, h-2, bp);
            String[] lb = {"H", "S", "V"};
            for (int i = 0; i < 3; i++) { float y = h*((2*i+1)/6f); c.drawLine(24, y, w-24, y, lp); c.drawText(lb[i], 6, y+8, tp); }
            c.drawCircle(w-18, h-18, 18, cp);
        }
        public boolean onTouchEvent(MotionEvent e) {
            if (isDrawing) return false;
            if (e.getAction() == MotionEvent.ACTION_DOWN) { lx = e.getRawX(); ly = e.getRawY(); res = (e.getX() > getWidth()-60 && e.getY() > getHeight()-60); return true; }
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                int dx = (int)(e.getRawX()-lx), dy = (int)(e.getRawY()-ly); lx = e.getRawX(); ly = e.getRawY();
                if (res) { hsvBoxParams.width = Math.max(160, hsvBoxParams.width+dx); hsvBoxParams.height = Math.max(110, hsvBoxParams.height+dy); }
                else { hsvBoxParams.x += dx; hsvBoxParams.y += dy; }
                windowManager.updateViewLayout(this, hsvBoxParams); return true;
            }
            return super.onTouchEvent(e);
        }
    }

    private class MarkerDotView extends View {
        String label; Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG), rp = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        float lx, ly;
        MarkerDotView(Context c, String l, int col) {
            super(c); label = l; fp.setColor(col); fp.setAlpha(190);
            rp.setColor(Color.WHITE); rp.setStyle(Paint.Style.STROKE); rp.setStrokeWidth(4f);
            tp.setColor(Color.WHITE); tp.setTextSize(24f); tp.setFakeBoldText(true); tp.setTextAlign(Paint.Align.CENTER);
        }
        protected void onDraw(Canvas c) {
            float cx = getWidth()/2f, cy = getHeight()/2f, r = Math.min(cx, cy)-4f;
            c.drawCircle(cx, cy, r, fp); c.drawCircle(cx, cy, r, rp); c.drawText(label, cx, cy+8f, tp);
        }
        public boolean onTouchEvent(MotionEvent e) {
            if (isDrawing) return false;
            WindowManager.LayoutParams p = (this == colorBtnMarker) ? colorBtnParams : brushBtnParams;
            if (e.getAction() == MotionEvent.ACTION_DOWN) { lx = e.getRawX(); ly = e.getRawY(); return true; }
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                p.x += (int)(e.getRawX()-lx); p.y += (int)(e.getRawY()-ly); lx = e.getRawX(); ly = e.getRawY();
                windowManager.updateViewLayout(this, p); return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
