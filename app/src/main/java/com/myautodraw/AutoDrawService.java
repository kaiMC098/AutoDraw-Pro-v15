package com.myautodraw;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class AutoDrawService extends AccessibilityService {
    private static AutoDrawService instance;

    private WindowManager windowManager;
    private View panelView;
    private WindowManager.LayoutParams panelParams;
    private TextView txtOverlayStatus, txtPickColorCode, btnMinimizePanel;
    private LinearLayout layoutManualPickBar, layoutOverlayBody;
    private TargetBoxView targetBoxView;
    private WindowManager.LayoutParams boxParams;
    private MarkerDotView colorBtnMarker;
    private WindowManager.LayoutParams colorBtnParams;
    private HsvSlidersBoxView hsvBoxView;
    private WindowManager.LayoutParams hsvBoxParams;
    private MarkerDotView brushBtnMarker;
    private WindowManager.LayoutParams brushBtnParams;

    private boolean overlaysAttached = false;
    private boolean markersVisible = true;
    private boolean panelCollapsed = false;
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

    public static boolean hasVisibleMenu() {
        return instance != null && instance.overlaysAttached && instance.panelView != null;
    }

    public static void ensureOverlaysVisible() {
        if (instance != null) {
            instance.mainHandler.post(() -> instance.restoreAllOverlays());
        }
    }

    public static void forceReinitOverlays() {
        if (instance != null) {
            instance.mainHandler.post(() -> {
                instance.cleanupViews();
                instance.initOverlays();
            });
        }
    }

    public void restoreAllOverlays() {
        if (!overlaysAttached || panelView == null) {
            initOverlays();
            return;
        }
        try {
            panelView.setVisibility(View.VISIBLE);
            panelCollapsed = false;
            if (layoutOverlayBody != null) layoutOverlayBody.setVisibility(View.VISIBLE);
            if (btnMinimizePanel != null) btnMinimizePanel.setText("—");
            markersVisible = true;
            updateModeButtonUi();
            applyMarkerVisibility();
            setPassThrough(false);
        } catch (Exception e) {
            cleanupViews();
            initOverlays();
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        mainHandler.post(this::initOverlays);
    }

    private int resolveOverlayWindowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void initOverlays() {
        if (overlaysAttached) return;
        try {
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

            int type = resolveOverlayWindowType();
            Context themedCtx = new ContextThemeWrapper(this, R.style.AppTheme);

            targetBoxView = new TargetBoxView(themedCtx);
            boxParams = createParams(DrawRepository.boxW, DrawRepository.boxH, DrawRepository.boxX, DrawRepository.boxY, type);
            try {
                windowManager.addView(targetBoxView, boxParams);
            } catch (Exception firstErr) {
                if (!Settings.canDrawOverlays(this)) {
                    throw firstErr;
                }
                type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                boxParams = createParams(DrawRepository.boxW, DrawRepository.boxH, DrawRepository.boxX, DrawRepository.boxY, type);
                windowManager.addView(targetBoxView, boxParams);
            }

            hsvBoxView = new HsvSlidersBoxView(themedCtx);
            hsvBoxParams = createParams(DrawRepository.hsvW, DrawRepository.hsvH, DrawRepository.hsvX, DrawRepository.hsvY, type);
            windowManager.addView(hsvBoxView, hsvBoxParams);

            colorBtnMarker = new MarkerDotView(themedCtx, "Màu", Color.parseColor("#EC4899"));
            colorBtnParams = createParams(96, 96, DrawRepository.colorBtnX, DrawRepository.colorBtnY, type);
            windowManager.addView(colorBtnMarker, colorBtnParams);

            brushBtnMarker = new MarkerDotView(themedCtx, "Bút", Color.parseColor("#2563EB"));
            brushBtnParams = createParams(96, 96, DrawRepository.brushBtnX, DrawRepository.brushBtnY, type);
            windowManager.addView(brushBtnMarker, brushBtnParams);

            View inflated = LayoutInflater.from(themedCtx).inflate(R.layout.overlay_panel, null);
            panelParams = createParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, 36, 90, type);
            windowManager.addView(inflated, panelParams);
            panelView = inflated;
            overlaysAttached = true;

            try {
                ImageView avatar = panelView.findViewById(R.id.imgOverlayAvatar);
                Drawable appIcon = getPackageManager().getApplicationIcon(getPackageName());
                if (avatar != null && appIcon != null) avatar.setImageDrawable(appIcon);
            } catch (Exception ignored) {}

            View dragHeader = panelView.findViewById(R.id.btnDragHandle);
            layoutOverlayBody = panelView.findViewById(R.id.layoutOverlayBody);
            btnMinimizePanel = panelView.findViewById(R.id.btnMinimizePanel);
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

            dragHeader.setOnTouchListener(new View.OnTouchListener() {
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

            btnMinimizePanel.setOnClickListener(v -> {
                panelCollapsed = !panelCollapsed;
                layoutOverlayBody.setVisibility(panelCollapsed ? View.GONE : View.VISIBLE);
                btnMinimizePanel.setText(panelCollapsed ? "+" : "—");
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
                txtOverlayStatus.setText("Đang vẽ từ trên xuống dưới (1px)...");
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

            Toast.makeText(this, "Đã bật Menu Vẽ Nổi v15 (@ellenjoe023)!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            cleanupViews();
            Toast.makeText(this, "Lỗi bật Menu Vẽ: " + e.getClass().getSimpleName() + " - " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void cleanupViews() {
        overlaysAttached = false;
        for (View v : new View[]{panelView, targetBoxView, hsvBoxView, colorBtnMarker, brushBtnMarker}) {
            if (windowManager != null && v != null) {
                try { windowManager.removeView(v); } catch (Exception ignored) {}
            }
        }
        panelView = null;
        targetBoxView = null;
        hsvBoxView = null;
        colorBtnMarker = null;
        brushBtnMarker = null;
    }

    private void updateModeButtonUi() {
        if (btnHsv == null || txtOverlayStatus == null) return;
        boolean auto = DrawRepository.isAutoHsvBuilda();
        btnHsv.setText(auto ? "Màu:Tự động" : "Màu:Tay");
        txtOverlayStatus.setText(auto
                ? "Tự động (@ellenjoe023): Căn khung cam H-S-V, chấm [Màu] & [Bút] rồi bấm Vẽ."
                : "Thủ công (@ellenjoe023): Đặt [Khung vẽ] lên giấy vẽ rồi bấm Vẽ.");
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
                txtOverlayStatus.setText("Hoàn tất vẽ toàn bộ tranh! (@ellenjoe023)");
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
            txtOverlayStatus.setText("Chọn màu " + (idx + 1) + "/" + total + " trên Builda rồi bấm [Vẽ tiếp ▶]");
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
                    public void onCompleted(GestureDescription g) { if (done !
