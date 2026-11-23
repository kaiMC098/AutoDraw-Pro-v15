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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class AutoDrawService extends AccessibilityService {
    private static AutoDrawService instance;

    private WindowManager windowManager;
    private View panelView;
    private WindowManager.LayoutParams panelParams;
    private TextView txtOverlayStatus, txtPickColorCode;
    private LinearLayout layoutManualPickBar;
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
    private Button btnHsv, btnContinue, btnSkipColor;
    private View viewColorPreview;
    private Runnable resumeAction;
    private Runnable skipAction;
    private int screenW = 1080, screenH = 2400;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static boolean isRunning() {
        return instance != null;
    }

    public static void ensureOverlaysVisible() {
        if (instance != null) {
            instance.mainHandler.post(() -> instance.restoreAllOverlays());
        }
    }

    public void restoreAllOverlays() {
        if (windowManager == null) return;
        try {
            if (panelView == null) {
                initOverlays();
                return;
            }
            panelView.setVisibility(View.VISIBLE);
            markersVisible = true;
            updateModeButtonUi();
            applyMarkerVisibility();
            setPassThrough(false);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        if (!Settings.canDrawOverlays(this)) return;
        initOverlays();
    }

    private void initOverlays() {
        if (panelView != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        if (!DrawRepository.hasSavedPositions) {
            DrawRepository.boxW = (int) (screenW * 0.72f);
            DrawRepository.boxH = DrawRepository.boxW;
            DrawRepository.boxX = (screenW - DrawRepository.boxW) / 2;
            DrawRepository.boxY = (int) (screenH * 0.24f);

            DrawRepository.hsvW = (int) (screenW * 0.44f);
            DrawRepository.hsvH = (int) (screenH * 0.11f);
            DrawRepository.hsvX = (int) (screenW * 0.26f);
            DrawRepository.hsvY = (int) (screenH * 0.36f);

            DrawRepository.colorBtnX = (int) (screenW * 0.69f);
            DrawRepository.colorBtnY = (int) (screenH * 0.84f);

            DrawRepository.brushBtnX = (int) (screenW * 0.11f);
            DrawRepository.brushBtnY = (int) (screenH * 0.84f);
            DrawRepository.hasSavedPositions = true;
        }

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        targetBoxView = new TargetBoxView(this);
        boxParams = createParams(DrawRepository.boxW, DrawRepository.boxH, DrawRepository.boxX, DrawRepository.boxY, type);
        windowManager.addView(targetBoxView, boxParams);

        hsvBoxView = new HsvSlidersBoxView(this);
        hsvBoxParams = createParams(DrawRepository.hsvW, DrawRepository.hsvH, DrawRepository.hsvX, DrawRepository.hsvY, type);
        windowManager.addView(hsvBoxView, hsvBoxParams);

        colorBtnMarker = new MarkerDotView(this, "Màu", Color.parseColor("#EC4899"));
        colorBtnParams = createParams(96, 96, DrawRepository.colorBtnX, DrawRepository.colorBtnY, type);
        windowManager.addView(colorBtnMarker, colorBtnParams);

        brushBtnMarker = new MarkerDotView(this, "Bút", Color.parseColor("#2563EB"));
        brushBtnParams = createParams(96, 96, DrawRepository.brushBtnX, DrawRepository.brushBtnY, type);
        windowManager.addView(brushBtnMarker, brushBtnParams);

        panelView = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null);
        panelParams = createParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, 36, 90, type);
        windowManager.addView(panelView, panelParams);

        TextView drag = panelView.findViewById(R.id.btnDragHandle);
        Button btnToggle = panelView.findViewById(R.id.btnToggleMarkers);
        Button btnStart = panelView.findViewById(R.id.btnStartDraw);
        Button btnStop = panelView.findViewById(R.id.btnStopDraw);
        txtOverlayStatus = panelView.findViewById(R.id.txtOverlayStatus);
        layoutManualPickBar = panelView.findViewById(R.id.layoutManualPickBar);
        txtPickColorCode = panelView.findViewById(R.id.txtPickColorCode);
        btnHsv = panelView.findViewById(R.id.btnColorModeSwitch);
        btnContinue = panelView.findViewById(R.id.btnContinueDraw);
        btnSkipColor = panelView.findViewById(R.id.btnSkipColor);
        viewColorPreview = panelView.findViewById(R.id.viewColorPreview);

        drag.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy; float tx, ty;
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    ix = panelParams.x; iy = panelParams.y;
                    tx = e.getRawX(); ty = e.getRawY();
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    panelParams.x = ix + (int) (e.getRawX() - tx);
                    panelParams.y = iy + (int) (e.getRawY() - ty);
                    windowManager.updateViewLayout(panelView, panelParams);
                    return true;
                }
                return false;
            }
        });

        updateModeButtonUi();
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
            markersVisible = true;
            updateModeButtonUi();
            applyMarkerVisibility();
        });

        btnStart.setOnClickListener(v -> {
            if (isDrawing) return;
            List<ColorLayer> layers = DrawRepository.getEnabledLayers();
            if (layers == null || layers.isEmpty()) {
                Toast.makeText(this, "Chưa có ảnh! Hãy vào app chọn ảnh trước.", Toast.LENGTH_SHORT).show();
                return;
            }
            startColorDrawing(layers);
        });

        btnContinue.setOnClickListener(v -> {
            Runnable r = resumeAction;
            if (r == null) return;
            resumeAction = null;
            skipAction = null;
            hidePickUi();
            txtOverlayStatus.setText("Đang vẽ từng pixel từ trên xuống dưới...");
            r.run();
        });

        btnSkipColor.setOnClickListener(v -> {
            Runnable s = skipAction;
            if (s == null) return;
            resumeAction = null;
            skipAction = null;
            hidePickUi();
            txtOverlayStatus.setText("Đã bỏ qua màu, chuyển màu kế...");
            s.run();
        });

        btnStop.setOnClickListener(v -> {
            isDrawing = false;
            resumeAction = null;
            skipAction = null;
            hidePickUi();
            setPassThrough(false);
            applyMarkerVisibility();
            txtOverlayStatus.setText("Đã dừng.");
        });
    }

    private void updateModeButtonUi() {
        if (btnHsv == null || txtOverlayStatus == null) return;
        boolean auto = DrawRepository.isAutoHsvBuilda();
        btnHsv.setText(auto ? "Màu:Tự động" : "Màu:Tay");
        txtOverlayStatus.setText(auto
                ? "Tự động: Đặt khung cam H-S-V khớp 3 thanh trượt của Builda, chấm [Màu] & [Bút] lên icon dưới."
                : "Thủ công: Đặt [Khung vẽ] lên giấy vẽ rồi bấm Vẽ. Mỗi màu sẽ dừng để bạn tự chọn.");
    }

    private void applyMarkerVisibility() {
        if (targetBoxView == null) return;
        int vis = markersVisible ? View.VISIBLE : View.GONE;
        targetBoxView.setVisibility(vis);
        int hsvVis = (markersVisible && DrawRepository.isAutoHsvBuilda()) ? View.VISIBLE : View.GONE;
        hsvBoxView.setVisibility(hsvVis);
        colorBtnMarker.setVisibility(hsvVis);
        brushBtnMarker.setVisibility(hsvVis);
    }

    private void hidePickUi() {
        if (layoutManualPickBar != null) layoutManualPickBar.setVisibility(View.GONE);
    }

    private WindowManager.LayoutParams createParams(int w, int h, int x, int y, int type) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = x;
        p.y = y;
        return p;
    }

    private void setPassThrough(boolean pt) {
        updatePt(targetBoxView, boxParams, pt, 0.18f);
        updatePt(hsvBoxView, hsvBoxParams, pt, 0.0f);
        updatePt(colorBtnMarker, colorBtnParams, pt, 0.15f);
        updatePt(brushBtnMarker, brushBtnParams, pt, 0.15f);
    }

    private void updatePt(View v, WindowManager.LayoutParams p, boolean pt, float alpha) {
        if (v == null || p == null) return;
        if (pt) {
            p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            v.setAlpha(alpha);
        } else {
            p.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            v.setAlpha(1.0f);
        }
        try { windowManager.updateViewLayout(v, p); } catch (Exception ignored) {}
    }

    private void startColorDrawing(List<ColorLayer> layers) {
        if (!markersVisible) {
            markersVisible = true;
            applyMarkerVisibility();
        }
        float w = targetBoxView.getWidth(), h = targetBoxView.getHeight(), ratio = DrawRepository.getAspectRatio();
        if (w <= 0 || h <= 0) return;

        isDrawing = true;
        setPassThrough(true);
        int[] loc = new int[2];
        targetBoxView.getLocationOnScreen(loc);
        float drawW = w, drawH = w / ratio;
        if (drawH > h) {
            drawH = h;
            drawW = h * ratio;
        }
        processLayer(layers, 0, loc[0] + (w - drawW) / 2f, loc[1] + (h - drawH) / 2f, drawW, drawH);
    }

    private void processLayer(List<ColorLayer> layers, int idx, float ox, float oy, float dw, float dh) {
        if (!isDrawing || idx >= layers.size()) {
            isDrawing = false;
            mainHandler.post(() -> {
                hidePickUi();
                setPassThrough(false);
                applyMarkerVisibility();
                txtOverlayStatus.setText("Hoàn tất vẽ toàn bộ tranh!");
                Toast.makeText(this, "Đã vẽ xong!", Toast.LENGTH_SHORT).show();
            });
            return;
        }
        ColorLayer layer = layers.get(idx);
        Runnable startStrokes = () -> drawStroke(layers, idx, 0, 0, ox, oy, dw, dh);
        Runnable skipThisLayer = () -> processLayer(layers, idx + 1, ox, oy, dw, dh);

        if (DrawRepository.isAutoHsvBuilda()) {
            mainHandler.post(() -> txtOverlayStatus.setText(
                    "Màu " + (idx + 1) + "/" + layers.size() + ": tự chỉnh " + layer.getHexString()
                            + " (H:" + layer.hue + " S:" + layer.saturation + " V:" + layer.value + ")"
            ));
            autoSelectColor(layer, startStrokes);
        } else {
            manualSelectColor(layer, idx, layers.size(), startStrokes, skipThisLayer);
        }
    }

    private void manualSelectColor(ColorLayer layer, int idx, int total, Runnable done, Runnable onSkip) {
        resumeAction = done;
        skipAction = onSkip;
        final String nl = String.valueOf((char) 10);
        mainHandler.post(() -> {
            setPassThrough(true);
            viewColorPreview.setBackgroundColor(layer.color);
            txtPickColorCode.setText(layer.getHexString() + nl + "H:" + layer.hue + " S:" + layer.saturation + " V:" + layer.value);
            layoutManualPickBar.setVisibility(View.VISIBLE);
            txtOverlayStatus.setText("Bước chọn màu " + (idx + 1) + "/" + total + ": Hãy chọn màu như ô mẫu trên Builda rồi bấm [Vẽ tiếp ▶]");
        });
    }

    private void autoSelectColor(ColorLayer layer, Runnable done) {
        PointF cBtn = centerOf(colorBtnMarker);
        tap(cBtn.x, cBtn.y, () -> mainHandler.postDelayed(() -> {
            if (!isDrawing) return;
            applyHsv(layer, () -> {
                if (!isDrawing) return;
                PointF bBtn = centerOf(brushBtnMarker);
                tap(bBtn.x, bBtn.y, () -> mainHandler.postDelayed(done, 450L));
            });
        }, 550L));
    }

    private void applyHsv(ColorLayer layer, Runnable done) {
        int[] loc = new int[2];
        hsvBoxView.getLocationOnScreen(loc);
        final float left = loc[0] + 28f;
        final float trackW = Math.max(40f, hsvBoxView.getWidth() - 56f);
        final float top = loc[1], h = hsvBoxView.getHeight();
        final float yH = top + h * (1f / 6f);
        final float yS = top + h * (3f / 6f);
        final float yV = top + h * (5f / 6f);

        final float fH = layer.hue / 360f;
        final float fS = layer.saturation / 100f;
        final float fV = layer.value / 100f;

        mainHandler.postDelayed(() -> {
            if (!isDrawing) return;
            setSliderAccurate(left, trackW, yH, fH, () -> mainHandler.postDelayed(() -> {
                if (!isDrawing) return;
                setSliderAccurate(left, trackW, yS, fS, () -> mainHandler.postDelayed(() -> {
                    if (!isDrawing) return;
                    setSliderAccurate(left, trackW, yV, fV, done);
                }, 160L));
            }, 160L));
        }, 250L);
    }

    private void setSliderAccurate(float left, float trackW, float y, float targetFrac, Runnable done) {
        float right = left + trackW;
        float targetX = left + Math.max(0f, Math.min(1f, targetFrac)) * trackW;
        slide(right, left, y, 140L, () -> {
            if (!isDrawing) return;
            if (targetFrac <= 0.01f) {
                done.run();
            } else {
                mainHandler.postDelayed(() -> slide(left, targetX, y, 180L, done), 90L);
            }
        });
    }

    private float clampX(float x) { return Math.max(1f, Math.min(screenW - 1f, x)); }
    private float clampY(float y) { return Math.max(1f, Math.min(screenH - 1f, y)); }

    private void slide(float fx, float tx, float y, long dur, Runnable done) {
        Path p = new Path();
        p.moveTo(clampX(fx), clampY(y));
        p.lineTo(clampX(tx), clampY(y));
        boolean ok = dispatchGesture(
                new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, dur)).build(),
                new GestureResultCallback() {
                    public void onCompleted(GestureDescription g) { if (done != null) done.run(); }
                    public void onCancelled(GestureDescription g) { isDrawing = false; mainHandler.post(() -> setPassThrough(false)); }
                }, null
        );
        if (!ok) failGesture();
    }

    private void tap(float x, float y, Runnable done) {
        Path p = new Path();
        p.moveTo(clampX(x), clampY(y));
        p.lineTo(clampX(x) + 1f, clampY(y) + 1f);
        boolean ok = dispatchGesture(
                new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 60L)).build(),
                new GestureResultCallback() {
                    public void onCompleted(GestureDescription g) { if (done != null) done.run(); }
                    public void onCancelled(GestureDescription g) { isDrawing = false; mainHandler.post(() -> setPassThrough(false)); }
                }, null
        );
        if (!ok) failGesture();
    }

    private void failGesture() {
        isDrawing = false;
        mainHandler.post(() -> {
            hidePickUi();
            setPassThrough(false);
            txtOverlayStatus.setText("Lỗi gửi thao tác chạm. Hãy bật lại quyền Trợ năng.");
        });
    }

    private PointF centerOf(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return new PointF(loc[0] + v.getWidth() / 2f, loc[1] + v.getHeight() / 2f);
    }

    private void drawStroke(List<ColorLayer> layers, int lIdx, int sIdx, int retryCount, float ox, float oy, float dw, float dh) {
        if (!isDrawing) {
            mainHandler.post(() -> setPassThrough(false));
            return;
        }
        List<List<PointF>> strokes = layers.get(lIdx).strokes;
        if (sIdx >= strokes.size()) {
            mainHandler.postDelayed(() -> processLayer(layers, lIdx + 1, ox, oy, dw, dh), 160L);
            return;
        }
        if (sIdx % 12 == 0) {
            final int total = strokes.size();
            mainHandler.post(() -> txtOverlayStatus.setText(
                    "Đang vẽ màu " + (lIdx + 1) + "/" + layers.size() + " — pixel/nét " + (sIdx + 1) + "/" + total
            ));
        }
        List<PointF> st = strokes.get(sIdx);
        if (st.isEmpty()) {
            mainHandler.post(() -> drawStroke(layers, lIdx, sIdx + 1, 0, ox, oy, dw, dh));
            return;
        }

        Path path = new Path();
        PointF f = st.get(0);
        float px = clampX(ox + f.x * dw), py = clampY(oy + f.y * dh), length = 0f;
        path.moveTo(px, py);
        for (int i = 1; i < st.size(); i++) {
            float nx = clampX(ox + st.get(i).x * dw), ny = clampY(oy + st.get(i).y * dh);
            length += (float) Math.hypot(nx - px, ny - py);
            path.lineTo(nx, ny);
            px = nx;
            py = ny;
        }
        if (length < 0.8f) path.lineTo(px + 0.8f, py);

        long base = DrawRepository.getStrokeDurationMs();
        long dur = (long) Math.max((float) base, Math.min(12000f, base + length * (base / 45f)));
        boolean ok;
        try {
            ok = dispatchGesture(
                    new GestureDescription.Builder()
                            .addStroke(new GestureDescription.StrokeDescription(path, 0, dur))
                            .build(),
                    new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription g) {
                            mainHandler.postDelayed(
                                    () -> drawStroke(layers, lIdx, sIdx + 1, 0, ox, oy, dw, dh),
                                    14L
                            );
                        }

                        @Override
                        public void onCancelled(GestureDescription g) {
                            if (!isDrawing) {
                                mainHandler.post(() -> setPassThrough(false));
                            } else if (retryCount < 6) {
                                mainHandler.postDelayed(
                                        () -> drawStroke(layers, lIdx, sIdx, retryCount + 1, ox, oy, dw, dh),
                                        35L
                                );
                            } else {
                                failGesture();
                            }
                        }
                    },
                    null
            );

            if (!ok) {
                if (retryCount < 6) {
                    mainHandler.postDelayed(
                            () -> drawStroke(layers, lIdx, sIdx, retryCount + 1, ox, oy, dw, dh),
                            35L
                    );
                } else {
                    failGesture();
                }
            }
        } catch (RuntimeException e) {
            failGesture();
        }
    }

    @Override
    public void onInterrupt() {
        isDrawing = false;
        resumeAction = null;
        skipAction = null;
        mainHandler.post(() -> {
            hidePickUi();
            setPassThrough(false);
            applyMarkerVisibility();
            if (txtOverlayStatus != null) {
                txtOverlayStatus.setText("Dịch vụ trợ năng bị gián đoạn.");
            }
        });
    }
}
