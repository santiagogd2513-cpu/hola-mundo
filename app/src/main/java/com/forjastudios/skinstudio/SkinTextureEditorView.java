package com.forjastudios.skinstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class SkinTextureEditorView extends View {
    public enum Tool { BRUSH, BUCKET }
    private final Paint paint=new Paint();
    private SkinCanvasModel model;
    private Tool tool=Tool.BRUSH;
    private int brushSize=1;
    private OnTextureChangedListener listener;
    public interface OnTextureChangedListener { void onChanged(); }
    public SkinTextureEditorView(Context c,AttributeSet a){super(c,a);init();}
    public SkinTextureEditorView(Context c){super(c);init();}
    private void init(){setLayerType(View.LAYER_TYPE_SOFTWARE,null);paint.setAntiAlias(false);setBackgroundColor(Color.parseColor("#09182A"));}
    public void bindModel(SkinCanvasModel m){model=m;invalidate();}
    public void setTool(Tool t){tool=t;invalidate();}
    public void setBrushSize(int size){brushSize=Math.max(1,Math.min(5,size));}
    public void setOnTextureChangedListener(OnTextureChangedListener l){listener=l;}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);if(model==null)return;
        float cell=Math.min(getWidth()/64f,getHeight()/64f);float ox=(getWidth()-cell*64)/2f,oy=(getHeight()-cell*64)/2f;
        for(int y=0;y<64;y++)for(int x=0;x<64;x++){
            paint.setColor(((x/4+y/4)&1)==0?Color.rgb(35,49,65):Color.rgb(19,31,45));
            c.drawRect(ox+x*cell,oy+y*cell,ox+(x+1)*cell,oy+(y+1)*cell,paint);
        }
        paint.setColor(Color.WHITE);paint.setFilterBitmap(false);
        c.drawBitmap(model.getSkinBitmap(),null,new android.graphics.RectF(ox,oy,ox+64*cell,oy+64*cell),paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);paint.setColor(Color.argb(65,180,220,255));
        for(int x=0;x<=64;x+=4)c.drawLine(ox+x*cell,oy,ox+x*cell,oy+64*cell,paint);
        for(int y=0;y<=64;y+=4)c.drawLine(ox,oy+y*cell,ox+64*cell,oy+y*cell,paint);
        paint.setStyle(Paint.Style.FILL);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(model==null)return false;
        float cell=Math.min(getWidth()/64f,getHeight()/64f),ox=(getWidth()-cell*64)/2f,oy=(getHeight()-cell*64)/2f;
        int x=(int)((e.getX()-ox)/cell),y=(int)((e.getY()-oy)/cell);
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE){
            if(x<0||x>=64||y<0||y>=64)return true;
            if(tool==Tool.BUCKET&&e.getActionMasked()==MotionEvent.ACTION_DOWN)model.floodFill(x,y);
            else if(tool==Tool.BRUSH&&e.getActionMasked()==MotionEvent.ACTION_DOWN||tool==Tool.BRUSH&&e.getActionMasked()==MotionEvent.ACTION_MOVE){
                int r=brushSize/2;for(int yy=y-r;yy<=y+r;yy++)for(int xx=x-r;xx<=x+r;xx++)model.paintPixel(xx,yy);
            }
            invalidate();if(listener!=null)listener.onChanged();return true;
        }
        return true;
    }
}