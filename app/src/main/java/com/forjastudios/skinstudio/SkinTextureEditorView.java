package com.forjastudios.skinstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class SkinTextureEditorView extends View {
    public enum Tool { BRUSH, BUCKET, PICKER }
    private final Paint paint = new Paint();
    private SkinCanvasModel model;
    private Tool tool = Tool.BRUSH;
    private int brushSize = 1;
    private float lastX = -1, lastY = -1;
    private OnTextureChangedListener listener;

    public interface OnTextureChangedListener { void onChanged(); }

    public SkinTextureEditorView(Context c, AttributeSet a) { super(c, a); init(); }
    public SkinTextureEditorView(Context c) { super(c); init(); }

    private void init() {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        paint.setAntiAlias(false);
        setBackgroundColor(Color.parseColor("#09182A"));
        setFocusable(true);
    }

    public void bindModel(SkinCanvasModel m) { model = m; invalidate(); }
    public void setTool(Tool t) { if (t != null) tool = t; lastX = -1; lastY = -1; invalidate(); }
    public Tool getTool() { return tool; }
    public void setBrushSize(int size) { brushSize = Math.max(1, Math.min(9, size)); }
    public void setOnTextureChangedListener(OnTextureChangedListener l) { listener = l; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (model == null || getWidth() <= 0 || getHeight() <= 0) return;

        float cell = Math.min(getWidth() / 64f, getHeight() / 64f);
        float ox = (getWidth() - cell * 64f) / 2f;
        float oy = (getHeight() - cell * 64f) / 2f;

        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                paint.setColor(((x / 4 + y / 4) & 1) == 0
                    ? Color.rgb(35, 49, 65) : Color.rgb(19, 31, 45));
                c.drawRect(ox + x * cell, oy + y * cell,
                    ox + (x + 1) * cell, oy + (y + 1) * cell, paint);
            }
        }

        paint.setColor(Color.WHITE);
        paint.setFilterBitmap(false);
        c.drawBitmap(model.getSkinBitmap(), null,
            new RectF(ox, oy, ox + 64f * cell, oy + 64f * cell), paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(Color.argb(65, 180, 220, 255));
        for (int x = 0; x <= 64; x += 4)
            c.drawLine(ox + x * cell, oy, ox + x * cell, oy + 64f * cell, paint);
        for (int y = 0; y <= 64; y += 4)
            c.drawLine(ox, oy + y * cell, ox + 64f * cell, oy + y * cell, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (model == null || e == null) return false;
        float cell = Math.min(getWidth() / 64f, getHeight() / 64f);
        if (cell <= 0f) return false;
        float ox = (getWidth() - cell * 64f) / 2f;
        float oy = (getHeight() - cell * 64f) / 2f;
        int x = (int) ((e.getX() - ox) / cell);
        int y = (int) ((e.getY() - oy) / cell);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!inside(x, y)) return true;
                getParent().requestDisallowInterceptTouchEvent(true);
                lastX = x;
                lastY = y;
                if (tool == Tool.BUCKET) {
                    model.floodFill(x, y);
                    changed();
                } else if (tool == Tool.PICKER) {
                    model.setActiveColor(model.getPixelColor(x, y));
                    changed();
                } else {
                    paintLine(x, y, x, y);
                    changed();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (tool == Tool.BRUSH && lastX >= 0 && inside(x, y)) {
                    paintLine(Math.round(lastX), Math.round(lastY), x, y);
                    lastX = x;
                    lastY = y;
                    changed();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lastX = -1;
                lastY = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return true;
    }

    private void paintLine(int x0, int y0, int x1, int y1) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            stamp(x0, y0);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private void stamp(int x, int y) {
        int low = (brushSize - 1) / 2;
        int high = brushSize / 2;
        for (int yy = y - low; yy <= y + high; yy++)
            for (int xx = x - low; xx <= x + high; xx++)
                model.paintPixel(xx, yy);
    }

    private boolean inside(int x, int y) {
        return x >= 0 && x < 64 && y >= 0 && y < 64;
    }

    private void changed() {
        invalidate();
        if (listener != null) listener.onChanged();
    }
}