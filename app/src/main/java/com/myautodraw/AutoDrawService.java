package com.myautodraw;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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
        Button btnHsv = panelView.findViewById(R.id.btnColorModeSwitch);
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

        btnToggle.setOnClickListener(v -> {
            if (isDrawing) return;
            markersVisible = !markersVisible;
            int vis = markersVisible ? View.VISIBLE : View.GONE;
            targetBoxView.setVisibility(vis);
            int hsvVis = (markersVisible && DrawRepository.isAutoHsvBuilda()) ? View.VISIBLE : View.GONE;
            hsvBoxView.setVisibility(hsvVis); colorBtnMarker.setVisibility(hsvVis); brushBtnMarker.setVisibility(hsvVis);
        });

        btnHsv.setOnClickListener(v -> {
            if (isDrawing) return;
            boolean next = !DrawRepository.isAutoHsvBuilda();
            DrawRepository.setAutoHsvBuilda(next);
            btnHsv.setText(next ? "HSV:Bật" : "HSV:Tắt");
            int vis = (markersVisible && next) ? View.VISIBLE : View.GONE;
            hsvBoxView.setVisibility(vis); colorBtnMarker.setVisibility(vis); brushBtnMarker.setVisibility(vis);
        });

        btnStart.setOnClickListener(v -> {
            if (isDrawing) return;
            List<ColorLayer> layers = DrawRepository.getEnabledLayers();
            if (layers == null || layers.isEmpty()) { Toast.makeText(this, "Chưa có ảnh!", Toast.LENGTH_SHORT).show(); return; }
            startColorDrawing(layers);
        });

        btnStop.setOnClickListener(v -> { isDrawing = false; setPassThrough(false); txtOverlayStatus.setText("Đã dừng."); });
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
        isDrawing = true;
        setPassThrough(true);
        int[] loc = new int[2];
        targetBoxView.getLocationOnScreen(loc);
        float w = targetBoxView.getWidth(), h = targetBoxView.getHeight(), ratio = DrawRepository.getAspectRatio();
        float drawW = w, drawH = w / ratio;
        if (drawH > h) { drawH = h; drawW = h * ratio; }
        processLayer(layers, 0, loc[0] + (w - drawW) / 2f, loc[1] + (h - drawH) / 2f, drawW, drawH);
    }

    private void processLayer(List<ColorLayer> layers, int idx, float ox, float oy, float dw, float dh) {
        if (!isDrawing || idx >= layers.size()) {
            isDrawing = false;
            mainHandler.post(() -> { setPassThrough(false); txtOverlayStatus.setText("Hoàn tất!"); });
            return;
        }
        ColorLayer layer = layers.get(idx);
        mainHandler.post(() -> txtOverlayStatus.setText("Màu " + (idx + 1) + "/" + layers.size() + " H:" + layer.hue + " S:" + layer.saturation + " V:" + layer.value));

        if (DrawRepository.isAutoHsvBuilda()) {
            PointF cBtn = centerOf(colorBtnMarker);
            tap(cBtn.x, cBtn.y, () -> {
                if (!isDrawing) return;
                applyHsv(layer, () -> {
                    if (!isDrawing) return;
                    PointF bBtn = centerOf(brushBtnMarker);
                    tap(bBtn.x, bBtn.y, () -> mainHandler.postDelayed(() -> drawStroke(layers, idx, 0, ox, oy, dw, dh), 220L));
                });
            });
        } else {
            drawStroke(layers, idx, 0, ox, oy, dw, dh);
        }
    }

    private void applyHsv(ColorLayer layer, Runnable done) {
        int[] loc = new int[2];
        hsvBoxView.getLocationOnScreen(loc);
        float left = loc[0] + 24f, trackW = Math.max(40f, hsvBoxView.getWidth() - 48f);
        float top = loc[1], h = hsvBoxView.getHeight();
        float yH = top + h * (1f / 6f), yS = top + h * (3f / 6f), yV = top + h * (5f / 6f);
        float xH = left + (layer.hue / 360f) * trackW;
        float xS = left + (layer.saturation / 100f) * trackW;
        float xV = left + (layer.value / 100f) * trackW;

        mainHandler.postDelayed(() -> {
            if (!isDrawing) return;
            slide(left + trackW * 0.5f, xH, yH, () -> mainHandler.postDelayed(() ->
                slide(left + trackW * 0.5f, xS, yS, () -> mainHandler.postDelayed(() ->
                    slide(left + trackW * 0.5f, xV, yV, done), 140L)), 140L));
        }, 320L);
    }

    private void slide(float fx, float tx, float y, Runnable done) {
        Path p = new Path(); p.moveTo(fx, y); p.lineTo(tx, y);
        dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 90L)).build(),
            new GestureResultCallback() {
                public void onCompleted(GestureDescription g) { if (done != null) done.run(); }
                public void onCancelled(GestureDescription g) { isDrawing = false; mainHandler.post(() -> setPassThrough(false)); }
            }, null);
    }

    private void tap(float x, float y, Runnable done) {
        Path p = new Path(); p.moveTo(x, y); p.lineTo(x + 1f, y + 1f);
        dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 40L)).build(),
            new GestureResultCallback() {
                public void onCompleted(GestureDescription g) { if (done != null) done.run(); }
                public void onCancelled(GestureDescription g) { isDrawing = false; mainHandler.post(() -> setPassThrough(false)); }
            }, null);
    }

    private PointF centerOf(View v) {
        int[] loc = new int[2]; v.getLocationOnScreen(loc);
        return new PointF(loc[0] + v.getWidth() / 2f, loc[1] + v.getHeight() / 2f);
    }

    private void drawStroke(List<ColorLayer> layers, int lIdx, int sIdx, float ox, float oy, float dw, float dh) {
        if (!isDrawing) { mainHandler.post(() -> setPassThrough(false)); return; }
        List<List<PointF>> strokes = layers.get(lIdx).strokes;
        if (sIdx >= strokes.size()) {
            mainHandler.postDelayed(() -> processLayer(layers, lIdx + 1, ox, oy, dw, dh), 120L);
            return;
        }
        List<PointF> st = strokes.get(sIdx);
        if (st.isEmpty()) { drawStroke(layers, lIdx, sIdx + 1, ox, oy, dw, dh); return; }
        Path path = new Path();
        PointF f = st.get(0);
        path.moveTo(ox + f.x * dw, oy + f.y * dh);
        if (st.size() == 1) path.lineTo(ox + f.x * dw + 0.5f, oy + f.y * dh + 0.5f);
        else for (int i = 1; i < st.size(); i++) path.lineTo(ox + st.get(i).x * dw, oy + st.get(i).y * dh);

        long base = DrawRepository.getStrokeDurationMs();
        long dur = Math.min(2500L, Math.max(base, st.size() * (base / 3L + 4L)));
        dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, dur)).build(),
            new GestureResultCallback() {
                public void onCompleted(GestureDescription g) { mainHandler.postDelayed(() -> drawStroke(layers, lIdx, sIdx + 1, ox, oy, dw, dh), 6L); }
                public void onCancelled(GestureDescription g) { isDrawing = false; mainHandler.post(() -> setPassThrough(false)); }
            }, null);
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
