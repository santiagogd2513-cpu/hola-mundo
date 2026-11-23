package com.libertyuv.android;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int PICK_RPF=10,PICK_KEY=11,SAVE_RAW=12;
    private LinearLayout root,list; private TextView title,details,status;
    private File workingCopy; private byte[] key; private Rpf7Archive archive; private Rpf7Archive.Node selected;
    private final Deque<Rpf7Archive.Node> folders=new ArrayDeque<>();

    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(Color.rgb(9,14,29));getWindow().setNavigationBarColor(Color.rgb(9,14,29));build();welcome();}
    private void build(){
        root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(18),dp(18),dp(18),dp(12));root.setBackgroundColor(Color.rgb(9,14,29));setContentView(root);
        root.addView(label("LIBERTYUV",27,Color.WHITE,true));root.addView(label("RPF WORKSPACE  •  ANDROID",11,Color.rgb(117,180,255),true));
        LinearLayout bar=new LinearLayout(this);bar.setPadding(0,dp(14),0,dp(12));root.addView(bar);
        Button keyBtn=button("Cargar clave"),openBtn=button("Abrir RPF");bar.addView(keyBtn,new LinearLayout.LayoutParams(0,dp(48),1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.leftMargin=dp(8);bar.addView(openBtn,p);
        keyBtn.setOnClickListener(v->pick(PICK_KEY,"application/octet-stream"));openBtn.setOnClickListener(v->pick(PICK_RPF,"*/*"));
        title=label("Sin archivo abierto",18,Color.WHITE,true);root.addView(title);
        details=label("Carga tu clave local de 32 bytes y después abre una copia RPF7.",13,Color.rgb(169,183,207),false);root.addView(details);
        status=label("Listo.",12,Color.rgb(117,180,255),false);root.addView(status);
        ScrollView scroll=new ScrollView(this);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,0,1);sp.topMargin=dp(12);root.addView(scroll,sp);
        list=new LinearLayout(this);list.setOrientation(1);scroll.addView(list);
        Button export=button("Exportar elemento seleccionado (RAW)");LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,dp(48));ep.topMargin=dp(10);root.addView(export,ep);export.setOnClickListener(v->exportSelected());
    }
    private void welcome(){list.removeAllViews();info("Explorador RPF7","Carga una clave local y abre un RPF7.");info("Modo seguro","La app trabaja sobre una copia en caché y no modifica el archivo original.");info("Límite actual","La compresión Xbox XMem/LZX, audio AWC y reconstrucción todavía no están habilitados.");}
    private void pick(int code,String mime){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);startActivityForResult(i,code);}
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        try{
            if(req==PICK_KEY){try(InputStream in=getContentResolver().openInputStream(uri)){key=readLimited(in,64);}if(key.length!=32)throw new IOException("La clave debe tener exactamente 32 bytes.");status.setText("Clave cargada en memoria; no se sube al repositorio.");}
            else if(req==PICK_RPF){if(key==null)throw new IOException("Primero carga tu clave de 32 bytes.");closeArchive();workingCopy=new File(getCacheDir(),"libertyuv-working-copy.rpf");try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(workingCopy)){copy(in,out);}archive=Rpf7Archive.open(workingCopy,key);folders.clear();folders.push(archive.getRoot());selected=null;render();status.setText("RPF7 abierto en modo lectura.");}
            else if(req==SAVE_RAW){if(archive==null||selected==null)throw new IOException("No hay archivo seleccionado.");try(OutputStream out=getContentResolver().openOutputStream(uri)){archive.exportRaw(selected,out);}status.setText("Exportación RAW terminada. Si estaba comprimido/cifrado, sigue siendo RAW.");}
        }catch(Exception e){status.setText("Error: "+e.getMessage());Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void render(){
        if(archive==null||folders.isEmpty()){welcome();return;}list.removeAllViews();Rpf7Archive.Node folder=folders.peek();Rpf7Archive.Header h=archive.getHeader();
        title.setText(workingCopy.getName());details.setText("RPF7 • "+h.entriesCount+" entradas • "+(h.platformBit==1?"Xbox 360":"PS3")+" • shift "+h.nameShift);
        if(folders.size()>1){Button up=button("← Carpeta anterior");list.addView(up);up.setOnClickListener(v->{folders.pop();selected=null;render();});}
        info("Ubicación",folder.name);
        for(Rpf7Archive.Node n:folder.children){
            Button b=button((n.directory?"▸  ":"◇  ")+n.name);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setTextSize(14);list.addView(b,new LinearLayout.LayoutParams(-1,dp(46)));
            TextView meta=label(n.directory?"CARPETA":n.displayType()+" • "+human(n.displaySize()),11,Color.rgb(145,164,194),false);meta.setPadding(dp(12),dp(2),0,dp(8));list.addView(meta);
            b.setOnClickListener(v->{if(n.directory){folders.push(n);selected=null;render();}else{selected=n;status.setText("Seleccionado: "+n.name+" • "+n.displayType());}});
        }
        if(folder.children.isEmpty())info("Carpeta vacía","No hay entradas.");
    }
    private void exportSelected(){
        if(archive==null||selected==null){Toast.makeText(this,"Selecciona primero un archivo.",Toast.LENGTH_SHORT).show();return;}
        String suffix=selected.resource?".resource.raw":(selected.storedSize==0?"":".compressed.raw");
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/octet-stream");i.putExtra(Intent.EXTRA_TITLE,selected.name.replaceAll("[^a-zA-Z0-9._-]","_")+suffix);startActivityForResult(i,SAVE_RAW);
    }
    private void info(String h,String body){list.addView(label(h,14,Color.WHITE,true));TextView t=label(body,12,Color.rgb(160,177,205),false);t.setPadding(0,dp(4),0,dp(12));list.addView(t);}
    private TextView label(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(28,48,82)));return b;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private String human(long n){if(n<1024)return n+" B";if(n<1048576)return String.format(Locale.US,"%.1f KB",n/1024.0);return String.format(Locale.US,"%.1f MB",n/1048576.0);}
    private byte[] readLimited(InputStream in,int max)throws Exception{if(in==null)throw new IOException("No se pudo abrir el archivo.");ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[128];int n;while((n=in.read(b))!=-1){if(o.size()+n>max)throw new IOException("Archivo de clave demasiado grande.");o.write(b,0,n);}return o.toByteArray();}
    private void copy(InputStream in,OutputStream out)throws Exception{if(in==null)throw new IOException("No se pudo leer el archivo.");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();}
    private void closeArchive(){if(archive!=null)try{archive.close();}catch(Exception ignored){}archive=null;}
    @Override protected void onDestroy(){closeArchive();super.onDestroy();}
}
