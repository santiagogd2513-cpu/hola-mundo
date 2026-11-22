package com.forjastudios.skinstudio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {
    private SkinCanvasModel skinModel;
    private SkinViewport3DView viewport3D;
    private SkinTextureEditorView textureEditor;
    private ColorCatalog colorCatalog;
    private TextView tvSelectedPart, tvSkinFormatBadge, tvColorInfo, tvToolDescription;
    private ActivityResultLauncher<String[]> loadSkinLauncher;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        skinModel = new SkinCanvasModel();
        colorCatalog = new ColorCatalog();
        viewport3D = findViewById(R.id.viewport3D);
        textureEditor = findViewById(R.id.textureEditor);
        tvSelectedPart = findViewById(R.id.tvSelectedPart);
        tvSkinFormatBadge = findViewById(R.id.tvSkinFormatBadge);
        tvColorInfo = findViewById(R.id.tvColorInfo);
        tvToolDescription = findViewById(R.id.tvToolDescription);

        viewport3D.bindModel(skinModel);
        textureEditor.bindModel(skinModel);
        textureEditor.setOnTextureChangedListener(() -> {
            refreshViews();
            if (textureEditor.getTool() == SkinTextureEditorView.Tool.PICKER)
                updateColorLabel("Color muestreado");
        });

        loadSkinLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onSkinPicked);

        setupSkinTypeSelector();
        setupPartSelector();
        setupPalette();
        setupUniversalWheel();
        setupTools();
        setupLoadButton();
        refreshUiLabels();
        updateColorLabel("Azul cielo");
        selectTool(SkinTextureEditorView.Tool.BRUSH, "Pincel");
    }

    private void setupSkinTypeSelector() {
        RadioGroup group = findViewById(R.id.rgSkinType);
        RadioButton classic = findViewById(R.id.rbClassic);
        if (classic != null) classic.setChecked(true);
        if (group != null) group.setOnCheckedChangeListener((g, id) -> {
            skinModel.setCurrentSkinType(id == R.id.rbSlim ? SkinType.SLIM : SkinType.CLASSIC);
            refreshUiLabels();
            refreshViews();
        });
    }

    private void setupPartSelector() {
        bindPart(R.id.btnPartHead, BodyPart.HEAD);
        bindPart(R.id.btnPartHair, BodyPart.HAIR);
        bindPart(R.id.btnPartFace, BodyPart.FACE);
        bindPart(R.id.btnPartChest, BodyPart.CHEST);
        bindPart(R.id.btnPartArms, BodyPart.ARMS);
        bindPart(R.id.btnPartHands, BodyPart.HANDS);
        bindPart(R.id.btnPartLegs, BodyPart.LEGS);
        bindPart(R.id.btnPartShoes, BodyPart.SHOES);

        Button fill = findViewById(R.id.btnFillPart);
        if (fill != null) fill.setOnClickListener(v -> {
            skinModel.setEraserMode(false);
            skinModel.fillPart(skinModel.getSelectedPart());
            setToolDescription("Rellenar parte");
            refreshViews();
            Toast.makeText(this, skinModel.getSelectedPart().getLabel() + " pintada completa",
                Toast.LENGTH_SHORT).show();
        });
    }

    private void bindPart(int id, BodyPart part) {
        Button b = findViewById(id);
        if (b != null) b.setOnClickListener(v -> {
            skinModel.setSelectedPart(part);
            refreshUiLabels();
            setToolDescription("Selección de parte");
        });
    }

    private void setupPalette() {
        HorizontalScrollView scroll = findViewById(R.id.hsvPaletteScroll);
        if (scroll == null) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int size = (int) (34 * getResources().getDisplayMetrics().density);
        int margin = (int) (3 * getResources().getDisplayMetrics().density);
        for (ColorCatalog.Swatch sw : colorCatalog.getSwatches()) {
            View chip = new View(this);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(size, size);
            p.setMargins(margin, 0, margin, 0);
            chip.setLayoutParams(p);
            chip.setBackgroundColor(sw.getColor());
            chip.setContentDescription(sw.getName());
            chip.setOnClickListener(v -> {
                skinModel.setActiveColor(sw.getColor());
                updateColorLabel(sw.getName());
                refreshViews();
            });
            row.addView(chip);
        }
        scroll.removeAllViews();
        scroll.addView(row);
    }

    private void setupUniversalWheel() {
        Button b = findViewById(R.id.btnUniversalWheel);
        if (b != null) b.setOnClickListener(v ->
            new UniversalColorWheelDialog().show(this, skinModel.getActiveColor(), (color, desc) -> {
                skinModel.setActiveColor(color);
                updateColorLabel(desc);
                refreshViews();
                Toast.makeText(this, "Color aplicado", Toast.LENGTH_SHORT).show();
            }));
    }

    private void setupTools() {
        Button brush = findViewById(R.id.btnBrush);
        Button bucket = findViewById(R.id.btnBucket);
        Button picker = findViewById(R.id.btnPicker);
        Button eraser = findViewById(R.id.btnEraser);
        Button reset = findViewById(R.id.btnResetCam);

        if (brush != null) brush.setOnClickListener(v ->
            selectTool(SkinTextureEditorView.Tool.BRUSH, "Pincel"));
        if (bucket != null) bucket.setOnClickListener(v ->
            selectTool(SkinTextureEditorView.Tool.BUCKET, "Cubeta"));
        if (picker != null) picker.setOnClickListener(v ->
            selectTool(SkinTextureEditorView.Tool.PICKER, "Cuentagotas"));
        if (eraser != null) eraser.setOnClickListener(v -> {
            skinModel.setEraserMode(true);
            textureEditor.setTool(SkinTextureEditorView.Tool.BRUSH);
            setToolDescription("Borrador");
        });
        if (reset != null) reset.setOnClickListener(v -> viewport3D.resetCamera());

        SeekBar size = findViewById(R.id.seekBrushSize);
        if (size != null) size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                textureEditor.setBrushSize(Math.max(1, p + 1));
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void selectTool(SkinTextureEditorView.Tool tool, String name) {
        skinModel.setEraserMode(false);
        textureEditor.setTool(tool);
        setToolDescription(name);
    }

    private void setToolDescription(String tool) {
        if (tvToolDescription == null) return;
        String description;
        switch (tool) {
            case "Cubeta":
                description = "Toca una zona para rellenar todos los píxeles conectados que tengan el mismo color.";
                break;
            case "Cuentagotas":
                description = "Toca cualquier píxel de la textura para tomar ese color y convertirlo en tu color activo.";
                break;
            case "Borrador":
                description = "Desliza sobre la textura para borrar píxeles y dejarlos transparentes.";
                break;
            case "Rellenar parte":
                description = "Aplica el color activo a todas las caras UV de la parte del cuerpo que seleccionaste.";
                break;
            case "Selección de parte":
                description = "Elegiste una zona del personaje. Usa «Rellenar parte» para colorearla completa.";
                break;
            default:
                description = "Dibuja sobre la textura con el dedo. Puedes arrastrar para trazar y cambiar el tamaño del pincel.";
                break;
        }
        tvToolDescription.setText(tool + " • " + description);
    }

    private void setupLoadButton() {
        Button b = findViewById(R.id.btnLoadSkin);
        if (b != null) b.setOnClickListener(v ->
            loadSkinLauncher.launch(new String[]{"image/png", "image/*"}));
    }

    private void onSkinPicked(Uri uri) {
        if (uri == null) return;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                Toast.makeText(this, "No se pudo abrir el archivo", Toast.LENGTH_LONG).show();
                return;
            }
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp == null) {
                Toast.makeText(this, "El archivo no es una imagen válida", Toast.LENGTH_LONG).show();
                return;
            }
            if (!skinModel.loadSkin(bmp)) {
                Toast.makeText(this, "Formato no compatible. Selecciona una skin PNG cuadrada 64×64 o clásica 64×32.",
                    Toast.LENGTH_LONG).show();
                return;
            }
            bmp.recycle();
            refreshViews();
            Toast.makeText(this, "Skin cargada. Ya puedes editarla.", Toast.LENGTH_SHORT).show();
        } catch (Exception ex) {
            Toast.makeText(this, "No se pudo cargar la skin: " + ex.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    private void updateColorLabel(String name) {
        if (tvColorInfo != null) tvColorInfo.setText(name + " • " +
            String.format("#%06X", 0xFFFFFF & skinModel.getActiveColor()));
    }

    private void refreshUiLabels() {
        if (tvSelectedPart != null) tvSelectedPart.setText(
            "Parte seleccionada: " + skinModel.getSelectedPart().getLabel() +
            " — " + skinModel.getSelectedPart().getDescription());
        if (tvSkinFormatBadge != null) tvSkinFormatBadge.setText(
            skinModel.getCurrentSkinType().getDisplayName() + " • " +
            skinModel.getCurrentSkinType().getCompatibility());
    }

    private void refreshViews() {
        if (viewport3D != null) viewport3D.invalidate();
        if (textureEditor != null) textureEditor.invalidate();
    }
}