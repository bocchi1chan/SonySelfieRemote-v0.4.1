package com.sonyselfieremote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class LiveViewSurface extends View {
    public interface TapListener { void onTapPercent(double x, double y); }
    private Bitmap frame;
    private int rotation=0;
    private boolean mirror=false, grid=false, histogram=false, zebra=false, fill=false;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix drawMatrix=new Matrix();
    private final Matrix inverseMatrix=new Matrix();
    private TapListener tapListener;
    private final int[] hist=new int[64];

    public LiveViewSurface(Context c, AttributeSet a){ super(c,a); setBackgroundColor(Color.BLACK); }
    public void setTapListener(TapListener l){ tapListener=l; }
    public void setFrame(Bitmap b){ frame=b; if(histogram && b!=null) buildHistogram(b); invalidate(); }
    public void rotate90(){ rotation=(rotation+90)%360; invalidate(); }
    public void setMirror(boolean b){ mirror=b; invalidate(); }
    public void setFill(boolean b){ fill=b; invalidate(); }
    public void setGrid(boolean b){ grid=b; invalidate(); }
    public void setHistogram(boolean b){ histogram=b; if(b&&frame!=null)buildHistogram(frame); invalidate(); }
    public void setZebra(boolean b){ zebra=b; invalidate(); }

    private void updateMatrix(){
        drawMatrix.reset();
        if(frame==null) return;
        float bw=frame.getWidth(), bh=frame.getHeight();
        drawMatrix.postTranslate(-bw/2f,-bh/2f);
        if(mirror) drawMatrix.postScale(-1f,1f);
        drawMatrix.postRotate(rotation);
        boolean swap=(rotation%180)!=0;
        float rw=swap?bh:bw, rh=swap?bw:bh;
        float scale=fill ? Math.max(getWidth()/rw, getHeight()/rh) : Math.min(getWidth()/rw, getHeight()/rh);
        drawMatrix.postScale(scale,scale);
        drawMatrix.postTranslate(getWidth()/2f,getHeight()/2f);
        drawMatrix.invert(inverseMatrix);
    }

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        if(frame==null) return;
        updateMatrix();
        c.drawBitmap(frame,drawMatrix,paint);
        if(grid) drawGrid(c);
        if(zebra) drawZebra(c);
        if(histogram) drawHistogram(c);
    }

    private void drawGrid(Canvas c){
        paint.setColor(0x99FFFFFF); paint.setStrokeWidth(1.5f); paint.setStyle(Paint.Style.STROKE);
        c.drawLine(getWidth()/3f,0,getWidth()/3f,getHeight(),paint);
        c.drawLine(getWidth()*2f/3f,0,getWidth()*2f/3f,getHeight(),paint);
        c.drawLine(0,getHeight()/3f,getWidth(),getHeight()/3f,paint);
        c.drawLine(0,getHeight()*2f/3f,getWidth(),getHeight()*2f/3f,paint);
    }

    private void drawZebra(Canvas c){
        // Lightweight preview zebra: sample the current JPEG on an 8px grid and hatch only near-clipped blocks.
        if(frame==null) return;
        Matrix inv=new Matrix(); drawMatrix.invert(inv);
        float[] p=new float[2];
        paint.setColor(0x99FFEB3B); paint.setStrokeWidth(2f); paint.setStyle(Paint.Style.STROKE);
        int step=14;
        for(int y=0;y<getHeight();y+=step){
            for(int x=0;x<getWidth();x+=step){
                p[0]=x+step/2f; p[1]=y+step/2f; inv.mapPoints(p);
                int ix=(int)p[0], iy=(int)p[1];
                if(ix>=0&&iy>=0&&ix<frame.getWidth()&&iy<frame.getHeight()){
                    int col=frame.getPixel(ix,iy);
                    int lum=(Color.red(col)*77 + Color.green(col)*150 + Color.blue(col)*29)>>8;
                    if(lum>=238) c.drawLine(x,y+step,x+step,y,paint);
                }
            }
        }
    }

    private void buildHistogram(Bitmap b){
        java.util.Arrays.fill(hist,0);
        int sx=Math.max(1,b.getWidth()/160), sy=Math.max(1,b.getHeight()/100);
        int max=1;
        for(int y=0;y<b.getHeight();y+=sy) for(int x=0;x<b.getWidth();x+=sx){
            int col=b.getPixel(x,y);
            int lum=(Color.red(col)*77 + Color.green(col)*150 + Color.blue(col)*29)>>8;
            int bin=Math.min(63,lum/4); hist[bin]++; max=Math.max(max,hist[bin]);
        }
    }

    private void drawHistogram(Canvas c){
        int max=1; for(int v:hist) max=Math.max(max,v);
        float w=Math.min(260,getWidth()*0.42f), h=90, left=12, bottom=getHeight()-12;
        paint.setStyle(Paint.Style.FILL); paint.setColor(0x88000000); c.drawRect(left,bottom-h,left+w,bottom,paint);
        paint.setColor(0xDDFFFFFF); float bw=w/hist.length;
        for(int i=0;i<hist.length;i++){
            float bar=h*hist[i]/max; c.drawRect(left+i*bw,bottom-bar,left+(i+1)*bw,bottom,paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getAction()==MotionEvent.ACTION_UP && frame!=null && tapListener!=null){
            updateMatrix(); float[] p={e.getX(),e.getY()}; inverseMatrix.mapPoints(p);
            if(p[0]>=0&&p[1]>=0&&p[0]<=frame.getWidth()&&p[1]<=frame.getHeight()){
                double x=100.0*p[0]/frame.getWidth(), y=100.0*p[1]/frame.getHeight();
                tapListener.onTapPercent(Math.max(0,Math.min(100,x)),Math.max(0,Math.min(100,y)));
            }
            return true;
        }
        return true;
    }
}
