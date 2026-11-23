package com.myautodraw;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private ImageView imgPreview;
    private TextView txtInfo, txtColorCountLabel, txtThreshLabel, txtSpeedLabel;
    private TextView txtSelectedColorHex, txtHueVal, txtSatVal, txtValVal;
    private View viewSelectedColorBox;
    private SeekBar seekColorCount, seekThresh, seekSpeed, seekHue, seekSat, seekVal;
    private RadioGroup rgMode, rgColorPickMode;
    private RadioButton rbColor, rbEdge, rbPickManual, rbPickAuto;
    private CheckBox cbIgnoreWhite, cbLayerEnabled;
    private LinearLayout layoutColorSwatches;
    private Button btnLoad, btnPerm, btnService, btnToggleAllColors;
    private Bitmap originalBitmap;
    private ColorLayer selectedLayer;
    private boolean updatingHsvUi = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::loadBitmapFromUri);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        imgPreview = findViewById(R.id.imgPreview);
        txtInfo = findViewById(R.id.txtInfo);
        txtColorCountLabel = findViewById(R.id.txtColorCountLabel);
        txtThreshLabel = findViewById(R.id.txtThreshLabel);
        txtSpeedLabel = findViewById(R.id.txtSpeedLabel);
        txtSelectedColorHex = findViewById(R.id.txtSelectedColorHex);
        txtHueVal = findViewById(R.id.txtHueVal);
        txtSatVal = findViewById(R.id.txtSatVal);
        txtValVal = findViewById(R.id.txtValVal);
        viewSelectedColorBox = findViewById(R.id.viewSelectedColorBox);
        seekColorCount = findViewById(R.id.seekColorCount);
        seekThresh = findViewById(R.id.seekThresh);
        seekSpeed = findViewById(R.id.seekSpeed);
        seekHue = findViewById(R.id.seekHue);
        seekSat = findViewById(R.id.seekSat);
        seekVal = findViewById(R.id.seekVal);
        rgMode = findViewById(R.id.rgMode);
        rbColor = findViewById(R.id.rbColor);
        rbEdge = findViewById(R.id.rbEdge);
        rgColorPickMode = findViewById(R.id.rgColorPickMode);
        rbPickManual = findViewById(R.id.rbPickManual);
        rbPickAuto = findViewById(R.id.rbPickAuto);
        cbIgnoreWhite = findViewById(R.id.cbIgnoreWhite);
        cbLayerEnabled = findViewById(R.id.cbLayerEnabled);
        layoutColorSwatches = findViewById(R.id.layoutColorSwatches);
        btnToggleAllColors = findViewById(R.id.btnToggleAllColors);
        btnLoad = findViewById(R.id.btnLoad);
        btnPerm = findViewById(R.id.btnPerm);
        btnService = findViewById(R.id.btnService);

        if (DrawRepository.isAutoHsvBuilda()) rbPickAuto.setChecked(true);
        else rbPickManual.setChecked(true);

        rgColorPickMode.setOnCheckedChangeListener((g, id) -> {
            DrawRepository.setAutoHsvBuilda(id == R.id.rbPickAuto);
        });

        btnToggleAllColors.setOnClickListener(v -> {
            List<ColorLayer> layers = DrawRepository.getLayers();
            if (layers == null || layers.isEmpty()) return;
            boolean anyDisabled = false;
            for (ColorLayer l : layers) if (!l.enabled) { anyDisabled = true; break; }
            for (ColorLayer l : layers) l.enabled = anyDisabled;
            refreshSelectedColorDisplay();
            refreshSwatchesUi();
            refreshPreviewOnly();
        });

        btnLoad.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        btnPerm.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } else Toast.makeText(this, "Đã cấp quyền Overlay!", Toast.LENGTH_SHORT).show();
        });
        btnService.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Vui lòng cấp quyền Overlay trước!", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        seekColorCount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { txtColorCountLabel.setText("Số lượng màu tách từ ảnh: " + (p + 2) + " màu"); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) { reprocessImage(); }
        });
        seekThresh.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { txtThreshLabel.setText("Độ chi tiết / Ngưỡng nét: " + p); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) { reprocessImage(); }
        });
        seekSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                long ms = Math.max(10L, p);
                DrawRepository.setStrokeDurationMs(ms);
                txtSpeedLabel.setText("Thời gian vẽ mỗi nét: " + ms + "ms");
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        SeekBar.OnSeekBarChangeListener hsvListener = new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                if (updatingHsvUi || selectedLayer == null) return;
                selectedLayer.updateFromHsv(seekHue.getProgress(), seekSat.getProgress(), seekVal.getProgress());
                refreshSelectedColorDisplay();
                refreshSwatchesUi();
                refreshPreviewOnly();
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        };
        seekHue.setOnSeekBarChangeListener(hsvListener);
        seekSat.setOnSeekBarChangeListener(hsvListener);
        seekVal.setOnSeekBarChangeListener(hsvListener);

        cbLayerEnabled.setOnCheckedChangeListener((b, isChecked) -> {
            if (updatingHsvUi || selectedLayer == null) return;
            selectedLayer.enabled = isChecked;
            refreshSwatchesUi();
            refreshPreviewOnly();
        });
        rgMode.setOnCheckedChangeListener((g, id) -> reprocessImage());
        cbIgnoreWhite.setOnCheckedChangeListener((b, c) -> reprocessImage());
    }

    @Override
    protected void onResume() {
        super.onResume();
        AutoDrawService.ensureOverlaysVisible();
        if (DrawRepository.isAutoHsvBuilda()) rbPickAuto.setChecked(true);
        else rbPickManual.setChecked(true);

        boolean overlayOk = Settings.canDrawOverlays(this);
        String expected = getPackageName() + "/" + AutoDrawService.class.getCanonicalName();
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean settingEnabled = !TextUtils.isEmpty(enabled) && (enabled.contains(expected) || enabled.contains(getPackageName() + "/.AutoDrawService"));
        boolean running = AutoDrawService.isRunning();
        btnPerm.setText(overlayOk ? "2. Quyền Overlay: ĐÃ BẬT ✓" : "2. Cấp quyền hiển thị nổi (Overlay)");
        if (running) {
            btnService.setText("3. Dịch vụ AutoDraw: ĐANG CHẠY ✓");
        } else if (settingEnabled) {
            btnService.setText("3. Bị hệ thống ngắt nền — Bấm để bật lại");
        } else {
            btnService.setText("3. Bật dịch vụ AutoDraw (Trợ năng)");
        }
    }

    private void loadBitmapFromUri(Uri uri) {
        if (uri == null) return;
        executor.execute(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (bmp != null) { originalBitmap = bmp; runOnUiThread(this::reprocessImage); }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Lỗi đọc ảnh: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void reprocessImage() {
        if (originalBitmap == null) return;
        int mode = rbColor.isChecked() ? ImageProcessor.MODE_COLOR : (rbEdge.isChecked() ? ImageProcessor.MODE_EDGE : ImageProcessor.MODE_THRESHOLD);
        txtInfo.setText("Đang tách phần màu & tạo nét vẽ...");
        executor.execute(() -> {
            ImageProcessor.ProcessResult res = ImageProcessor.process(originalBitmap, mode, seekThresh.getProgress(), seekColorCount.getProgress() + 2, cbIgnoreWhite.isChecked());
            DrawRepository.setLayers(res.layers, (float) originalBitmap.getWidth() / originalBitmap.getHeight());
            runOnUiThread(() -> {
                imgPreview.setImageBitmap(res.previewBitmap);
                selectedLayer = res.layers.isEmpty() ? null : res.layers.get(0);
                refreshSelectedColorDisplay();
                refreshSwatchesUi();
                txtInfo.setText("Sẵn sàng: " + res.layers.size() + " phần màu | " + res.totalStrokes + " nét (" + res.totalPoints + " điểm).");
            });
        });
    }

    private void refreshPreviewOnly() {
        List<ColorLayer> layers = DrawRepository.getLayers();
        if (layers != null && !layers.isEmpty()) imgPreview.setImageBitmap(ImageProcessor.renderPreview(layers, 140, 140));
    }

    private void refreshSelectedColorDisplay() {
        updatingHsvUi = true;
        if (selectedLayer != null) {
            viewSelectedColorBox.setBackgroundColor(selectedLayer.color);
            txtSelectedColorHex.setText("Màu chọn: " + selectedLayer.getHexString() + " (" + selectedLayer.strokes.size() + " nét)");
            seekHue.setProgress(selectedLayer.hue);
            seekSat.setProgress(selectedLayer.saturation);
            seekVal.setProgress(selectedLayer.value);
            txtHueVal.setText(String.valueOf(selectedLayer.hue));
            txtSatVal.setText(String.valueOf(selectedLayer.saturation));
            txtValVal.setText(String.valueOf(selectedLayer.value));
            cbLayerEnabled.setChecked(selectedLayer.enabled);
        }
        updatingHsvUi = false;
    }

    private void refreshSwatchesUi() {
        layoutColorSwatches.removeAllViews();
        List<ColorLayer> layers = DrawRepository.getLayers();
        if (layers == null) return;
        for (int i = 0; i < layers.size(); i++) {
            final ColorLayer layer = layers.get(i);
            TextView sw = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(96, 76);
            lp.setMargins(0, 0, 10, 0);
            sw.setLayoutParams(lp);
            sw.setGravity(android.view.Gravity.CENTER);
            sw.setTextSize(10f);
            sw.setText(layer.enabled ? ("#" + (i + 1) + "\n✓") : ("#" + (i + 1) + "\nTắt"));
            int br = (Color.red(layer.color) * 299 + Color.green(layer.color) * 587 + Color.blue(layer.color) * 114) / 1000;
            sw.setTextColor(br > 130 ? Color.BLACK : Color.WHITE);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(layer.color);
            bg.setCornerRadius(12f);
            bg.setStroke(layer == selectedLayer ? 6 : 2, Color.parseColor(layer == selectedLayer ? "#38BDF8" : "#475569"));
            sw.setBackground(bg);
            sw.setAlpha(layer.enabled ? 1.0f : 0.35f);
            sw.setOnClickListener(v -> { selectedLayer = layer; refreshSelectedColorDisplay(); refreshSwatchesUi(); });
            sw.setOnLongClickListener(v -> {
                layer.enabled = !layer.enabled;
                if (selectedLayer == layer) refreshSelectedColorDisplay();
                refreshSwatchesUi(); refreshPreviewOnly();
                return true;
            });
            layoutColorSwatches.addView(sw);
        }
    }

    @Override
    protected void onDestroy() { super.onDestroy(); executor.shutdownNow(); }
}
