package com.sonyselfieremote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class LiveViewReader implements Runnable {
    public interface Listener { void onFrame(Bitmap frame); void onError(Exception e); }
    private final String url;
    private final Listener listener;
    private volatile boolean running=true;
    private HttpURLConnection conn;

    public LiveViewReader(String url, Listener listener){ this.url=url; this.listener=listener; }
    public void stop(){ running=false; if(conn!=null) conn.disconnect(); }

    private static void readFully(InputStream in, byte[] b) throws Exception {
        int off=0;
        while(off<b.length){ int n=in.read(b,off,b.length-off); if(n<0) throw new java.io.EOFException(); off+=n; }
    }

    @Override public void run(){
        try {
            conn=(HttpURLConnection)new URL(url).openConnection();
            conn.setConnectTimeout(5000); conn.setReadTimeout(0); conn.connect();
            try (BufferedInputStream in=new BufferedInputStream(conn.getInputStream(), 256*1024)) {
                byte[] common=new byte[8];
                byte[] payload=new byte[128];
                while(running){
                    readFully(in,common);
                    readFully(in,payload);
                    int jpegSize=((payload[4]&0xff)<<16)|((payload[5]&0xff)<<8)|(payload[6]&0xff);
                    int padding=payload[7]&0xff;
                    if(jpegSize<=0 || jpegSize>2_000_000) throw new IllegalStateException("Invalid liveview JPEG size: "+jpegSize);
                    byte[] jpeg=new byte[jpegSize]; readFully(in,jpeg);
                    if(padding>0) readFully(in,new byte[padding]);
                    Bitmap bmp=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);
                    if(bmp!=null && listener!=null) listener.onFrame(bmp);
                }
            }
        } catch(Exception e){ if(running && listener!=null) listener.onError(e); }
    }
}
