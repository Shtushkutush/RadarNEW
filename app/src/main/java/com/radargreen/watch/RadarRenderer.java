package com.radargreen.watch;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/** 400-coordinate radar dial; light-weight Canvas rendering, scaled for any display. */
public final class RadarRenderer {
    private final RadarData data;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Calendar clock = Calendar.getInstance();
    private Float frozenSweepDegrees = null;
    private static final Map<Character,String[]> FONT = new HashMap<>();
    static {
        glyph('A',"01110","10001","10001","11111","10001","10001","10001");
        glyph('B',"11110","10001","10001","11110","10001","10001","11110");
        glyph('E',"11111","10000","10000","11110","10000","10000","11111");
        glyph('P',"11110","10001","10001","11110","10000","10000","10000");
        glyph('S',"01111","10000","10000","01110","00001","00001","11110");
        glyph('T',"11111","00100","00100","00100","00100","00100","00100");
        glyph('0',"01110","10001","10011","10101","11001","10001","01110");
        glyph('1',"00100","01100","00100","00100","00100","00100","01110");
        glyph('2',"01110","10001","00001","00010","00100","01000","11111");
        glyph('3',"11110","00001","00001","01110","00001","00001","11110");
        glyph('4',"00010","00110","01010","10010","11111","00010","00010");
        glyph('5',"11111","10000","10000","11110","00001","00001","11110");
        glyph('6',"01110","10000","10000","11110","10001","10001","01110");
        glyph('7',"11111","00001","00010","00100","01000","01000","01000");
        glyph('8',"01110","10001","10001","01110","10001","10001","01110");
        glyph('9',"01110","10001","10001","01111","00001","00001","01110");
        glyph('%',"11001","11010","00100","00100","01011","10011","00000");
        glyph('-',"00000","00000","00000","11111","00000","00000","00000");
        glyph(' ',"00000","00000","00000","00000","00000","00000","00000");
        glyph('+',"00000","00100","00100","11111","00100","00100","00000");
        glyph('O',"01110","10001","10001","10001","10001","10001","01110");
        glyph('U',"10001","10001","10001","10001","10001","10001","01110");
        glyph('C',"01111","10000","10000","10000","10000","10000","01111");
        glyph('.',"00000","00000","00000","00000","00000","00100","00100");
    }
    private static void glyph(char c, String... rows) { FONT.put(c, rows); }
    public RadarRenderer(RadarData data) { this.data = data; }

    public void draw(Canvas canvas, int width, int height) {
        draw(canvas, width, height, false);
    }

    public void draw(Canvas canvas, int width, int height, boolean ambient) {
        canvas.drawColor(Color.BLACK);
        float scale = Math.min(width, height) / 400f;
        canvas.save();
        canvas.translate(width/2f,height/2f);
        canvas.scale(scale,scale);
        Path clip = new Path(); clip.addCircle(0,0,199.5f,Path.Direction.CW);canvas.clipPath(clip);
        clock.setTimeInMillis(System.currentTimeMillis());
        int hourOfDay = clock.get(Calendar.HOUR_OF_DAY);
        boolean night = hourOfDay >= 22 || hourOfDay < 7;
        float styleIntensity = ambient ? (night ? .24f : .36f) : (night ? .46f : .91f);
        int baseGreen = night ? Color.rgb(50,116,73) : Color.rgb(55,175,92);
        float ms = clock.get(Calendar.MILLISECOND);
        float secs = clock.get(Calendar.SECOND) + ms/1000f;
        float mins = clock.get(Calendar.MINUTE) + secs/60f;
        float hours = (hourOfDay%12) + mins/60f;
        float liveSweep = secs*6f;
        float sweep = ambient ? frozenSweep(liveSweep) : liveSweep;
        if (!ambient) frozenSweepDegrees = liveSweep;

        paint.reset();paint.setAntiAlias(true);
        paint.setShader(new RadialGradient(0,0,196,
            new int[]{Color.rgb(1,night?12:22,night?4:9),Color.rgb(0,night?5:10,3),Color.BLACK},
            new float[]{0f,.72f,1f},Shader.TileMode.CLAMP));
        canvas.drawCircle(0,0,199,paint);paint.setShader(null);

        for(int r=42;r<=140;r+=24) ring(canvas,r,rgba(31,155,68,opacity(ambient?36:58,styleIntensity)),.6f);
        ring(canvas,168,rgba(48,190,89,opacity(ambient?66:123,styleIntensity)),ambient?.7f:.85f);
        ring(canvas,90,rgba(48,190,89,opacity(ambient?56:102,styleIntensity)),ambient?.7f:.85f);
        ring(canvas,181,rgba(28,110,55,opacity(ambient?42:68,styleIntensity)),.65f);
        for(int a=0;a<360;a+=30){
            float th=(float)Math.toRadians(a);
            stroke(canvas,0,0,(float)Math.sin(th)*180,-(float)Math.cos(th)*180,
                    rgba(47,150,80,opacity(a%90==0?(ambient?54:95):(ambient?14:24),styleIntensity)),a%90==0?1f:.5f);
        }

        if (!ambient) {
            for(int i=58;i>=0;i--){
                float age=i/58f;
                float th=(float)Math.toRadians(sweep-i*.94f);
                float strength=(1f-age)*(1f-age);
                stroke(canvas,0,0,(float)Math.sin(th)*166,-(float)Math.cos(th)*166,
                        rgba(46,205,93,opacity((int)(54*strength),styleIntensity)),1.5f+(1-age)*1.4f);
            }
        }
        float st=(float)Math.toRadians(sweep);
        float sx=(float)Math.sin(st)*168,sy=-(float)Math.cos(st)*168;
        stroke(canvas,0,0,sx,sy,rgba(65,222,112,opacity(ambient?44:108,styleIntensity)),ambient?2.5f:5f);
        stroke(canvas,0,0,sx,sy,rgba(116,235,140,opacity(ambient?88:202,styleIntensity)),ambient?.9f:1.25f);

        double ma=Math.toRadians(mins*6), ha=Math.toRadians(hours*30);
        float mx=(float)Math.sin(ma)*168, my=-(float)Math.cos(ma)*168;
        float hx=(float)Math.sin(ha)*90, hy=-(float)Math.cos(ha)*90;

        float minutePulse = ambient ? 0f : pulse(sweep,(float)(mins*6),night?2.0f:1.5f);
        float hourPulse = ambient ? 0f : pulse(sweep,(float)(hours*30),night?2.0f:1.5f);
        dot(canvas,mx,my,3.9f,minutePulse,night,ambient);
        dot(canvas,hx,hy,3.4f,hourPulse,night,ambient);
        dot(canvas,0,0,2.8f,0f,night,ambient);

        String battery=data.battery()<0?"BAT --%":"BAT "+data.battery()+"%";
        String outdoor=data.outdoorTemperatureText();
        Label batteryLabel=labelAt(hx,hy,battery,2.05f, -1);
        Label stepLabel=labelAt(mx,my,outdoor,1.9f, 1);
        if(overlaps(batteryLabel,stepLabel)){
            stepLabel=labelAt(mx,my,outdoor,1.9f,-1);
            if(overlaps(batteryLabel,stepLabel)) stepLabel.shiftY(stepLabel.y<=batteryLabel.y?-17:17);
        }
        drawLabel(canvas,hx,hy,batteryLabel,baseGreen,styleIntensity,hourPulse,ambient);
        drawLabel(canvas,mx,my,stepLabel,baseGreen,styleIntensity,minutePulse,ambient);

        for(int i=0;i<60;i++){
            float th=(float)Math.toRadians(i*6);
            boolean major=i%5==0;
            float inner=major?184:190,outer=195;
            stroke(canvas,(float)Math.sin(th)*inner,-(float)Math.cos(th)*inner,
                   (float)Math.sin(th)*outer,-(float)Math.cos(th)*outer,
                   rgba(85,205,116,opacity(major?(ambient?110:170):(ambient?70:110),styleIntensity)),major?2.2f:1f);
        }
        ring(canvas,198,rgba(36,182,80,opacity(ambient?118:188,styleIntensity)),1.25f);
        canvas.restore();
    }

    private float frozenSweep(float fallback) {
        if (frozenSweepDegrees == null) frozenSweepDegrees = fallback;
        return frozenSweepDegrees;
    }

    /** Returns 0..1 after the sweeping front has passed this moving target. */
    private float pulse(float sweep,float target,float fadeSec){
        float diff=((sweep-target)%360+360)%360;
        float elapsed=diff/6f;
        if(elapsed>fadeSec) return 0f;
        float t=elapsed/fadeSec;
        return (1-t)*(1-t);
    }
    private void dot(Canvas canvas,float x,float y,float r,float pulse,boolean night,boolean ambient){
        float brightness = ambient
                ? (night?.36f:.48f)
                : (night?.55f:.84f)+(night?.12f:.32f)*pulse;
        paint.reset();paint.setAntiAlias(true);
        paint.setShader(new RadialGradient(x,y,r*(ambient?2.6f:3.5f),
           new int[]{rgba(101,217,140,(int)((ambient?52:102)*brightness)),rgba(49,145,76,(int)((ambient?18:38)*brightness)),Color.TRANSPARENT},
           new float[]{0,.4f,1},Shader.TileMode.CLAMP));
        canvas.drawCircle(x,y,r*(ambient?2.6f:3.5f),paint);paint.setShader(null);
        paint.setColor(rgba(143,234,162,(int)((ambient?150:215)*brightness)));
        canvas.drawCircle(x,y,r,paint);
        if(!ambient && pulse>.05f){
            ringAt(canvas,x,y,r+3+8*(1-pulse),rgba(84,185,112,(int)((night?28:50)*pulse)),.65f);
        }
    }

    private static final class Label {
        float x,y; final float w,h; final String text; final float unit;
        Label(float x,float y,float w,float h,String text,float unit){this.x=x;this.y=y;this.w=w;this.h=h;this.text=text;this.unit=unit;}
        void shiftY(float v){y=Math.max(-171,Math.min(172-h,y+v));}
    }
    private Label labelAt(float dotX,float dotY,String str,float unit,int prefer){
        float textW=(str.length()*6-1)*unit;
        float gap=14;
        float y=Math.max(-152,Math.min(146-7*unit,dotY-8));
        float half= (float)Math.sqrt(Math.max(0, 174*174 -
                 Math.pow(Math.max(Math.abs(y),Math.abs(y+7*unit)),2)));
        if (textW > 2*half) {
            y = dotY < 0 ? -Math.min(65,Math.abs(dotY)*.4f) : Math.min(65,Math.abs(dotY)*.4f);
            half=(float)Math.sqrt(Math.max(0,174*174 -
                 Math.pow(Math.max(Math.abs(y),Math.abs(y+7*unit)),2)));
        }
        float minX=-half, maxX=half-textW;
        boolean right=prefer>0;
        float proposal=right?dotX+gap:dotX-gap-textW;
        if (proposal>maxX && dotX-gap-textW>=minX) proposal=dotX-gap-textW;
        else if(proposal<minX && dotX+gap<=maxX) proposal=dotX+gap;
        float x=Math.max(minX,Math.min(maxX,proposal));
        return new Label(x,y,textW,7*unit,str,unit);
    }
    private boolean overlaps(Label a,Label b){
        return b.x<a.x+a.w+5 && a.x<b.x+b.w+5 &&
                a.y<b.y+b.h+5 && b.y<a.y+a.h+5;
    }
    private void drawLabel(Canvas canvas,float dotX,float dotY,Label l,int green,float intensity,float pulse,boolean ambient){
        stroke(canvas,dotX,dotY,l.x>dotX?l.x-4:l.x+l.w+4,l.y+l.h/2,
                rgba(70,178,100,opacity(ambient?72:100+(int)(42*pulse),intensity)),ambient?.6f:.7f);
        dotText(canvas,l.text,l.x,l.y,l.unit,
                rgba(Math.min(255,Color.red(green)+25),Math.min(255,Color.green(green)+34),
                        Math.min(255,Color.blue(green)+24),opacity(ambient?145:180+(int)(30*pulse),intensity)));
    }
    private void dotText(Canvas canvas,String text,float x,float y,float unit,int color){
        paint.reset();paint.setAntiAlias(false);paint.setColor(color);paint.setStyle(Paint.Style.FILL);
        float spacing=unit;float radius=Math.max(.45f,unit*.31f);
        for(int i=0;i<text.length();i++){
            String[] glyph=FONT.get(text.charAt(i));if(glyph==null) glyph=FONT.get(' ');
            for(int row=0;row<7;row++) for(int col=0;col<5;col++) if(glyph[row].charAt(col)=='1')
                canvas.drawCircle(x+(i*6+col)*spacing,y+row*spacing,radius,paint);
        }
        paint.setAntiAlias(true);
    }
    private void ring(Canvas c,float r,int color,float width){ringAt(c,0,0,r,color,width);}
    private void ringAt(Canvas c,float x,float y,float r,int color,float width){
        paint.reset();paint.setAntiAlias(true);paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width);c.drawCircle(x,y,r,paint);
        paint.setStyle(Paint.Style.FILL);
    }
    private void stroke(Canvas c,float x1,float y1,float x2,float y2,int color,float width){
        paint.reset();paint.setAntiAlias(true);paint.setColor(color);paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);c.drawLine(x1,y1,x2,y2,paint);
    }
    private static int rgba(int r,int g,int b,int a){return Color.argb(Math.max(0,Math.min(255,a)),Math.max(0,Math.min(255,r)),Math.max(0,Math.min(255,g)),Math.max(0,Math.min(255,b)));}
    private static int opacity(int alpha,float intensity){return Math.round(alpha*intensity);}
}
