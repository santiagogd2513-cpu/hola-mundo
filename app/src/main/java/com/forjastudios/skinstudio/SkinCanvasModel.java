package com.forjastudios.skinstudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

public class SkinCanvasModel {
    public static final int SKIN_WIDTH = 64, SKIN_HEIGHT = 64;
    private Bitmap skinBitmap;
    private SkinType currentSkinType = SkinType.CLASSIC;
    private BodyPart selectedPart = BodyPart.HEAD;
    private int activeColor = Color.parseColor("#29B6F6");
    private boolean eraserMode = false;

    public SkinCanvasModel() {
        skinBitmap = Bitmap.createBitmap(SKIN_WIDTH, SKIN_HEIGHT, Bitmap.Config.ARGB_8888);
        initDefaultTemplate();
    }

    public void initDefaultTemplate() {
        skinBitmap.eraseColor(Color.TRANSPARENT);
        fillRect(8, 0, 16, 8, Color.parseColor("#4FC3F7"));
        fillRect(0, 8, 32, 16, Color.parseColor("#1565C0"));
        fillRect(20, 16, 28, 20, Color.parseColor("#1565C0"));
        fillRect(16, 20, 40, 32, Color.parseColor("#0D47A1"));
        fillRect(0, 20, 16, 32, Color.parseColor("#1E3A5F"));
        fillRect(40, 20, 56, 32, Color.parseColor("#1565C0"));
        fillRect(16, 52, 32, 64, Color.parseColor("#1E3A5F"));
        fillRect(32, 52, 48, 64, Color.parseColor("#1565C0"));
    }

    private void fillRect(int x0, int y0, int x1, int y1, int color) {
        for (int y = Math.max(0, y0); y < Math.min(SKIN_HEIGHT, y1); y++)
            for (int x = Math.max(0, x0); x < Math.min(SKIN_WIDTH, x1); x++)
                skinBitmap.setPixel(x, y, color);
    }

    public boolean paintPixel(int x, int y) {
        if (!inside(x, y)) return false;
        skinBitmap.setPixel(x, y, eraserMode ? Color.TRANSPARENT : activeColor);
        return true;
    }

    public void fillPart(BodyPart part) {
        if (part == null) return;
        int color = eraserMode ? Color.TRANSPARENT : activeColor;
        for (int[] r : part.getRegions(currentSkinType))
            fillRect(r[0], r[1], r[2], r[3], color);
    }

    public int floodFill(int startX, int startY) {
        if (!inside(startX, startY)) return 0;
        int target = skinBitmap.getPixel(startX, startY);
        int replacement = eraserMode ? Color.TRANSPARENT : activeColor;
        if (target == replacement) return 0;

        int[] queue = new int[SKIN_WIDTH * SKIN_HEIGHT];
        boolean[] seen = new boolean[queue.length];
        int head = 0, tail = 0;
        int start = startY * SKIN_WIDTH + startX;
        queue[tail++] = start;
        seen[start] = true;

        while (head < tail) {
            int p = queue[head++];
            int x = p % SKIN_WIDTH, y = p / SKIN_WIDTH;
            skinBitmap.setPixel(x, y, replacement);
            if (x > 0) tail = enqueue(p - 1, target, queue, seen, tail);
            if (x < 63) tail = enqueue(p + 1, target, queue, seen, tail);
            if (y > 0) tail = enqueue(p - 64, target, queue, seen, tail);
            if (y < 63) tail = enqueue(p + 64, target, queue, seen, tail);
        }
        return head;
    }

    private int enqueue(int p, int target, int[] q, boolean[] seen, int tail) {
        if (!seen[p] && skinBitmap.getPixel(p % 64, p / 64) == target) {
            seen[p] = true;
            q[tail++] = p;
        }
        return tail;
    }

    public int getPixelColor(int x, int y) {
        return inside(x, y) ? skinBitmap.getPixel(x, y) : activeColor;
    }

    private boolean inside(int x, int y) {
        return x >= 0 && x < SKIN_WIDTH && y >= 0 && y < SKIN_HEIGHT;
    }

    public boolean loadSkin(Bitmap source) {
        if (source == null) return false;
        int w = source.getWidth(), h = source.getHeight();
        if (w <= 0 || h <= 0 || (long) w != h && (long) w != (long) h * 2L)
            return false;

        int normalizedHeight = h * 2 == w ? 32 : 64;
        Bitmap normalized = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        normalized.eraseColor(Color.TRANSPARENT);
        Bitmap scaled = Bitmap.createScaledBitmap(source, 64, normalizedHeight, false);
        Canvas canvas = new Canvas(normalized);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(scaled, 0, 0, p);
        if (scaled != source && !scaled.isRecycled()) scaled.recycle();
        skinBitmap = normalized;
        eraserMode = false;
        return true;
    }

    public Bitmap getSkinBitmap() { return skinBitmap; }
    public SkinType getCurrentSkinType() { return currentSkinType; }
    public void setCurrentSkinType(SkinType type) { if (type != null) currentSkinType = type; }
    public BodyPart getSelectedPart() { return selectedPart; }
    public void setSelectedPart(BodyPart part) { if (part != null) selectedPart = part; }
    public int getActiveColor() { return activeColor; }
    public void setActiveColor(int color) { activeColor = color; eraserMode = false; }
    public boolean isEraserMode() { return eraserMode; }
    public void setEraserMode(boolean enabled) { eraserMode = enabled; }
}