package com.forjastudios.skinstudio;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public class UniversalColorWheelDialog {
    public interface OnUniversalColorSelectedListener { void onColorPicked(int color, String description); }
    private float currentHue=200f, currentSat=.85f, currentVal=.95f;
    private String blendModeLabel="Directo";

    public void show(Context context,int initialColor,OnUniversalColorSelectedListener listener) {
        float[] hsv=new float[3]; Color.colorToHSV(initialColor,hsv);
        currentHue=hsv[0]; currentSat=Math.max(.05f,hsv[1]); currentVal=Math.max(.10f,hsv[2]);
        blendModeLabel="Rueda Universal";
        LinearLayout root=new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#060D17"));
        int pad=(int)(14*context.getResources().getDisplayMetrics().density); root.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(context); title.setText("Rueda Universal & Combinaciones");
        title.setTextColor(Color.parseColor("#90CAF9")); title.setTextSize(16f); root.addView(title);
        TextView preview=new TextView(context); preview.setTextColor(Color.parseColor("#E3F2FD"));
        preview.setTextSize(12f); preview.setPadding(0,pad/2,0,pad/2);
        ColorWheelView wheel=new ColorWheelView(context,(h,s)->{
            currentHue=h; currentSat=s; blendModeLabel="Rueda HSV"; updatePreview(preview);
        });
        int size=(int)(220*context.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(size,size);
        wp.gravity=android.view.Gravity.CENTER_HORIZONTAL; wheel.setLayoutParams(wp); root.addView(wheel);
        TextView valueLabel=new TextView(context); valueLabel.setText("Brillo / Luminosidad");
        valueLabel.setTextColor(Color.parseColor("#81D4FA")); valueLabel.setTextSize(12f); root.addView(valueLabel);
        SeekBar value=new SeekBar(context); value.setMax(100); value.setProgress((int)(currentVal*100f));
        value.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean user){currentVal=Math.max(.05f,p/100f);wheel.setValue(currentVal);updatePreview(preview);}
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
        }); root.addView(value); root.addView(preview);
        LinearLayout row1=new LinearLayout(context); row1.setOrientation(LinearLayout.HORIZONTAL);
        Button complementary=makeButton(context,"Complemento");
        complementary.setOnClickListener(v->{currentHue=(currentHue+180f)%360f;blendModeLabel="Complementario";wheel.setHueSat(currentHue,currentSat);updatePreview(preview);});
        Button triadic=makeButton(context,"Triádico");
        triadic.setOnClickListener(v->{currentHue=(currentHue+120f)%360f;blendModeLabel="Triádico";wheel.setHueSat(currentHue,currentSat);updatePreview(preview);});
        Button analogous=makeButton(context,"Análogo");
        analogous.setOnClickListener(v->{currentHue=(currentHue+30f)%360f;blendModeLabel="Análogo";wheel.setHueSat(currentHue,currentSat);updatePreview(preview);});
        row1.addView(complementary);row1.addView(triadic);row1.addView(analogous);root.addView(row1);
        LinearLayout row2=new LinearLayout(context);row2.setOrientation(LinearLayout.HORIZONTAL);row2.setPadding(0,pad/2,0,0);
        Button mix=makeButton(context,"Mezclar 50/50");
        mix.setOnClickListener(v->{int mixed=blend(getColor(),initialColor,.5f);float[] m=new float[3];Color.colorToHSV(mixed,m);currentHue=m[0];currentSat=m[1];currentVal=m[2];blendModeLabel="Mezcla 50/50";wheel.setValue(currentVal);wheel.setHueSat(currentHue,currentSat);updatePreview(preview);});
        Button invert=makeButton(context,"Invertir RGB");
        invert.setOnClickListener(v->{int c=getColor();int inv=Color.rgb(255-Color.red(c),255-Color.green(c),255-Color.blue(c));float[] hsv2=new float[3];Color.colorToHSV(inv,hsv2);currentHue=hsv2[0];currentSat=hsv2[1];currentVal=hsv2[2];blendModeLabel="Invertido";wheel.setValue(currentVal);wheel.setHueSat(currentHue,currentSat);updatePreview(preview);});
        row2.addView(mix);row2.addView(invert);root.addView(row2);
        wheel.setValue(currentVal);wheel.setHueSat(currentHue,currentSat);updatePreview(preview);
        new AlertDialog.Builder(context,android.R.style.Theme_DeviceDefault_Dialog_Alert).setView(root)
          .setPositiveButton("Usar Color",(d,w)->{if(listener!=null)listener.onColorPicked(getColor(),blendModeLabel);})
          .setNegativeButton("Cancelar",null).show();
    }
    private Button makeButton(Context c,String label){
        Button b=new Button(c);b.setText(label);b.setTextSize(10f);b.setTextColor(Color.parseColor("#E3F2FD"));
        b.setBackgroundColor(Color.parseColor("#1565C0"));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f);
        p.setMargins(3,0,3,0);b.setLayoutParams(p);return b;
    }
    private int getColor(){return Color.HSVToColor(new float[]{currentHue,currentSat,currentVal});}
    private int blend(int a,int b,float ratio){
        float inv=1f-ratio;return Color.rgb(
          Math.max(0,Math.min(255,(int)(Color.red(a)*ratio+Color.red(b)*inv))),
          Math.max(0,Math.min(255,(int)(Color.green(a)*ratio+Color.green(b)*inv))),
          Math.max(0,Math.min(255,(int)(Color.blue(a)*ratio+Color.blue(b)*inv))));
    }
    private void updatePreview(TextView tv){
        int c=getColor();String hex=String.format("#%06X",(0xFFFFFF&c));tv.setTextColor(c);
        tv.setText("Modo: "+blendModeLabel+" | HEX: "+hex+" | H:"+Math.round(currentHue)+"° S:"+Math.round(currentSat*100)+"% V:"+Math.round(currentVal*100)+"%");
    }
    private static class ColorWheelView extends View {
        interface OnWheelPick{void onPick(float hue,float sat);}
        private final OnWheelPick callback;private final Paint marker=new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap bitmap;private float val=.95f,hue=200f,sat=.85f;
        ColorWheelView(Context c,OnWheelPick cb){super(c);callback=cb;marker.setStyle(Paint.Style.STROKE);marker.setStrokeWidth(4f);marker.setColor(Color.WHITE);}
        void setValue(float v){val=v;rebuild();invalidate();}
        void setHueSat(float h,float s){hue=h;sat=s;invalidate();}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);rebuild();}
        private void rebuild(){
            int size=Math.min(getWidth(),getHeight());if(size<=4)return;
            Bitmap b=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);float r=size/2f;
            for(int y=0;y<size;y++)for(int x=0;x<size;x++){
                float dx=x-r,dy=y-r,dist=(float)Math.sqrt(dx*dx+dy*dy);
                if(dist<=r){float s=Math.min(1f,dist/r);float a=(float)Math.toDegrees(Math.atan2(dy,dx));float h=(a+360f)%360f;b.setPixel(x,y,Color.HSVToColor(new float[]{h,s,val}));}
                else b.setPixel(x,y,Color.TRANSPARENT);
            }bitmap=b;
        }
        @Override protected void onDraw(Canvas c){super.onDraw(c);if(bitmap!=null)c.drawBitmap(bitmap,0,0,null);
            int size=Math.min(getWidth(),getHeight());if(size>4){float r=size/2f;double a=Math.toRadians(hue);c.drawCircle(r+(float)Math.cos(a)*sat*r,r+(float)Math.sin(a)*sat*r,10f,marker);}}
        @Override public boolean onTouchEvent(MotionEvent e){
            if(e==null)return false;int size=Math.min(getWidth(),getHeight());if(size<=4)return false;
            float r=size/2f,dx=e.getX()-r,dy=e.getY()-r,dist=(float)Math.sqrt(dx*dx+dy*dy);
            sat=Math.min(1f,dist/r);float a=(float)Math.toDegrees(Math.atan2(dy,dx));hue=(a+360f)%360f;invalidate();
            if(callback!=null)callback.onPick(hue,sat);return true;
        }
    }
}