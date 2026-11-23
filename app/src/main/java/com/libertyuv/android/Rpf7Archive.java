package com.libertyuv.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Defensive RPF7 table reader. Xbox XMem/LZX payload decoding is not yet provided. */
public final class Rpf7Archive implements AutoCloseable {
    public static final class Header {
        public final int entriesCount, platformBit, nameShift, namesLength;
        public final long flags;
        Header(int c, int p, int s, int n, long f) { entriesCount=c; platformBit=p; nameShift=s; namesLength=n; flags=f; }
    }
    public static final class Node {
        public final String name;
        public final boolean directory, resource, encrypted;
        public final long dataOffset, storedSize, unpackedSize, systemFlags, graphicsFlags;
        public final int tableIndex;
        public final List<Node> children = new ArrayList<>();
        Node(String n, boolean d, boolean r, boolean e, long o, long s, long u, long sf, long gf, int i) {
            name=n; directory=d; resource=r; encrypted=e; dataOffset=o; storedSize=s; unpackedSize=u; systemFlags=sf; graphicsFlags=gf; tableIndex=i;
        }
        public String displayType() {
            if (directory) return "CARPETA";
            if (resource) return "RECURSO RAGE";
            if (storedSize == 0) return "SIN COMPRESIÓN";
            return encrypted ? "COMPRIMIDO / CIFRADO" : "COMPRIMIDO";
        }
        public long displaySize() { return storedSize == 0 ? unpackedSize : storedSize; }
    }
    private final File file;
    private final RandomAccessFile input;
    private final Header header;
    private final Node root;
    private Rpf7Archive(File f, RandomAccessFile in, Header h, Node r) { file=f; input=in; header=h; root=r; }

    public static Header parseHeader(byte[] h) throws IOException {
        if (h == null || h.length < 16) throw new EOFException("Cabecera RPF7 incompleta.");
        if (h[0]!='R'||h[1]!='P'||h[2]!='F'||h[3]!='7') throw new IOException("Firma RPF7 inválida.");
        long count=u32be(h,4), info=u32be(h,8), flags=u32be(h,12);
        if (count<1 || count>500000) throw new IOException("Cantidad de entradas fuera de rango.");
        int platform=(int)((info>>>31)&1), shift=(int)((info>>>28)&7), names=(int)(info&0x0FFFFFFF);
        if (names<1 || names>64*1024*1024) throw new IOException("Tabla de nombres inválida.");
        return new Header((int)count,platform,shift,names,flags);
    }
    public static Rpf7Archive open(File file, byte[] key) throws Exception {
        if (key==null || key.length!=32) throw new IOException("La clave debe tener exactamente 32 bytes.");
        RandomAccessFile raf=new RandomAccessFile(file,"r"); boolean success=false;
        try {
            byte[] h=new byte[16]; raf.readFully(h); Header header=parseHeader(h);
            if (((header.flags>>>28)&15)==15) throw new IOException("El modo de 16 rondas todavía no está implementado.");
            long raw=(long)header.entriesCount*16L+header.namesLength, encryptedLength=(raw+15L)&~15L;
            if (encryptedLength>Integer.MAX_VALUE || 16L+encryptedLength>raf.length()) throw new IOException("La tabla excede el tamaño del RPF.");
            byte[] enc=new byte[(int)encryptedLength]; raf.seek(16); raf.readFully(enc);
            Cipher aes=Cipher.getInstance("AES/ECB/NoPadding"); aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"));
            byte[] toc=aes.doFinal(enc), entries=new byte[header.entriesCount*16], names=new byte[header.namesLength];
            System.arraycopy(toc,0,entries,0,entries.length); System.arraycopy(toc,entries.length,names,0,names.length);
            Node root=new Parser(raf,header,entries,names).parse();
            success=true; return new Rpf7Archive(file,raf,header,root);
        } finally { if (!success) raf.close(); }
    }
    public Header getHeader(){return header;} public Node getRoot(){return root;} public File getFile(){return file;}
    public void exportRaw(Node node, OutputStream out) throws IOException {
        if (node==null || node.directory) throw new IOException("Selecciona un archivo.");
        if (node.storedSize<0 || node.storedSize>Integer.MAX_VALUE || node.dataOffset<0 || node.dataOffset+node.storedSize>input.length())
            throw new IOException("Rango de datos inválido.");
        input.seek(node.dataOffset); byte[] buffer=new byte[65536]; long left=node.storedSize;
        while(left>0){int n=(int)Math.min(buffer.length,left);input.readFully(buffer,0,n);out.write(buffer,0,n);left-=n;}
        out.flush();
    }
    public void close() throws IOException { input.close(); }
    private static final class Parser {
        final RandomAccessFile source; final Header h; final byte[] entries,names; final Set<Integer> used=new HashSet<>();
        Parser(RandomAccessFile s,Header h,byte[] e,byte[] n){source=s;this.h=h;entries=e;names=n;}
        Node parse() throws IOException { Node r=make(0); if(!r.directory)throw new IOException("La entrada raíz no es una carpeta."); return r; }
        Node make(int index) throws IOException {
            if(index<0||index>=h.entriesCount)throw new IOException("Índice de entrada fuera de rango.");
            if(!used.add(index))throw new IOException("Referencia circular o entrada duplicada.");
            int p=index*16;
            long first=((long)(entries[p]&255)<<16)|((long)(entries[p+1]&255)<<8)|(entries[p+2]&255);
            boolean resource=((first>>>23)&1)!=0; long units=first&0x7FFFFF;
            long stored=((long)(entries[p+3]&255)<<16)|((long)(entries[p+4]&255)<<8)|(entries[p+5]&255);
            long nameOff=(((long)(entries[p+6]&255)<<8)|(entries[p+7]&255))<<h.nameShift;
            long f5=u32be(entries,p+8), f6=u32be(entries,p+12); String name=readName(nameOff);
            if(units==0x7FFFFF){
                if(resource)throw new IOException("Carpeta marcada como recurso.");
                if(f5>h.entriesCount||f6>h.entriesCount||f5+f6>h.entriesCount)throw new IOException("Rango de subentradas inválido: "+name);
                Node dir=new Node(name,true,false,false,0,0,0,0,0,index);
                for(int i=0;i<(int)f6;i++)dir.children.add(make((int)f5+i));
                return dir;
            }
            long offset=units<<9, size=resource?stored:(stored==0?f5:stored);
            if(offset>source.length()||size<0||offset+size>source.length())throw new IOException("Datos truncados: "+name);
            return new Node(name,false,resource,!resource&&stored!=0&&f6!=0,offset,size,f5,resource?f5:0,resource?f6:0,index);
        }
        String readName(long off) throws IOException {
            if(off<0||off>=names.length)throw new IOException("Desplazamiento de nombre inválido.");
            int a=(int)off,b=a;while(b<names.length&&names[b]!=0)b++;
            if(b==names.length)throw new IOException("Nombre sin terminador.");
            String n=new String(names,a,b-a,StandardCharsets.UTF_8);
            if(n.contains("/")||n.contains("\\")||n.equals(".")||n.equals(".."))throw new IOException("Nombre no seguro.");
            return n.isEmpty()?(a==0?"ROOT":"_"):n;
        }
    }
    private static long u32be(byte[] d,int p){return ((long)(d[p]&255)<<24)|((long)(d[p+1]&255)<<16)|((long)(d[p+2]&255)<<8)|(long)(d[p+3]&255);}
}
