package dev.falix.studiomobile;
import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Crisp native vector primitives, no emoji icons or borrowed identity assets. */
final class StudioIcon extends View {
    final String kind;final Paint paint=new Paint(3);int color;
    StudioIcon(Context c,String kind,int color){super(c);this.kind=kind;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.save();float s=Math.min(getWidth(),getHeight());canvas.translate((getWidth()-s)/2,(getHeight()-s)/2);canvas.scale(s/24,s/24);paint.setColor(color);paint.setStrokeWidth(1.7f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);
        switch(kind){
            case "logo":paint.setStyle(Paint.Style.FILL);paint.setColor(0xff0b0d12);canvas.drawRoundRect(new RectF(1,1,23,23),5,5,paint);paint.setColor(0xff4f7dff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.5f);Path logo=new Path();logo.moveTo(16,7.5f);logo.lineTo(9,7.5f);logo.quadTo(7.5f,7.5f,7.5f,9);logo.lineTo(7.5f,10.5f);logo.quadTo(7.5f,12,9,12);logo.lineTo(15,12);logo.quadTo(16.5f,12,16.5f,13.5f);logo.lineTo(16.5f,15);logo.quadTo(16.5f,16.5f,15,16.5f);logo.lineTo(8,16.5f);canvas.drawPath(logo,paint);break;
            case "moon":canvas.drawArc(new RectF(4,3,21,20),45,290,false,paint);path(canvas,new float[]{12,3,10,8,12,13,17,15,21,13});break;
            case "sun":canvas.drawCircle(12,12,4,paint);for(int k=0;k<8;k++){double a=k*Math.PI/4;canvas.drawLine(12+(float)Math.cos(a)*7,12+(float)Math.sin(a)*7,12+(float)Math.cos(a)*10,12+(float)Math.sin(a)*10,paint);}break;
            case "home":path(canvas,new float[]{3,11,12,3,21,11});path(canvas,new float[]{5,10,5,21,19,21,19,10});canvas.drawRect(10,14,14,21,paint);break;
            case "folder":path(canvas,new float[]{3,7,3,20,21,20,21,7,12,7,10,4,3,4,3,7});break;
            case "download":path(canvas,new float[]{12,3,12,15});path(canvas,new float[]{7,10,12,15,17,10});path(canvas,new float[]{4,16,4,21,20,21,20,16});break;
            case "plus":canvas.drawLine(12,5,12,19,paint);canvas.drawLine(5,12,19,12,paint);break;
            case "people":canvas.drawCircle(9,7,3,paint);canvas.drawArc(new RectF(3,12,15,24),180,180,false,paint);canvas.drawArc(new RectF(14,4,20,10),-90,180,false,paint);canvas.drawArc(new RectF(14,12,23,23),210,150,false,paint);break;
            case "clock":canvas.drawCircle(12,12,9,paint);path(canvas,new float[]{12,6,12,12,16,14});break;
            case "key":canvas.drawCircle(7,8,4,paint);path(canvas,new float[]{10,11,20,21,22,19,19,16,21,14,18,11});break;
            case "check":path(canvas,new float[]{5,12,10,17,20,6});break;
            case "arrow":path(canvas,new float[]{14,5,7,12,14,19});canvas.drawLine(7,12,21,12,paint);break;
            case "chevron":path(canvas,new float[]{7,9,12,14,17,9});break;
            case "cloud":path(canvas,new float[]{6,18,4,18,2,15,3,11,7,10,8,6,13,4,18,7,19,10,22,12,22,16,20,18,17,18});canvas.drawLine(12,11,12,21,paint);path(canvas,new float[]{9,18,12,21,15,18});break;
            case "file":path(canvas,new float[]{5,2,15,2,20,7,20,22,5,22,5,2});path(canvas,new float[]{15,2,15,7,20,7});canvas.drawLine(9,12,16,12,paint);canvas.drawLine(9,16,16,16,paint);break;
            case "refresh":canvas.drawArc(new RectF(4,4,20,20),45,285,false,paint);path(canvas,new float[]{20,3,20,9,14,9});break;
            case "cube":path(canvas,new float[]{12,3,21,7.5f,21,16.5f,12,21,3,16.5f,3,7.5f,12,3});canvas.drawLine(12,12,12,21,paint);canvas.drawLine(3,7.5f,12,12,paint);canvas.drawLine(21,7.5f,12,12,paint);break;
            case "model":path(canvas,new float[]{12,3,20,7,20,15,12,19,4,15,4,7,12,3});break;
            case "script":path(canvas,new float[]{6,2,15,2,19,6,19,22,6,22,6,2});path(canvas,new float[]{15,2,15,6,19,6});canvas.drawLine(9,11,16,11,paint);canvas.drawLine(9,14,16,14,paint);canvas.drawLine(9,17,14,17,paint);break;
            case "light":canvas.drawCircle(12,10,4,paint);canvas.drawLine(9,15,15,15,paint);canvas.drawLine(10,18,14,18,paint);canvas.drawLine(11,21,13,21,paint);break;
            case "camera":path(canvas,new float[]{3,7,8,7,10,4,14,4,16,7,21,7,21,19,3,19,3,7});canvas.drawCircle(12,13,4,paint);break;
            case "spawn":canvas.drawLine(7,3,7,21,paint);path(canvas,new float[]{7,4,19,7,7,11});break;
            case "sound":path(canvas,new float[]{10,4,5,9,5,15,10,20,10,4});canvas.drawArc(new RectF(10,6,19,18),-60,120,false,paint);canvas.drawArc(new RectF(10,3,24,21),-55,110,false,paint);break;
            case "gui":path(canvas,new float[]{3,5,21,5,21,17,3,17,3,5});canvas.drawLine(3,9,21,9,paint);canvas.drawCircle(6,7,1,paint);break;
            case "terrain":path(canvas,new float[]{3,18,8,10,12,15,16,8,21,18,3,18});break;
            case "service":canvas.drawCircle(12,12,9,paint);canvas.drawLine(3,12,21,12,paint);canvas.drawArc(new RectF(7,3,17,21),0,360,false,paint);canvas.drawArc(new RectF(7,3,17,21),120,120,false,paint);canvas.drawArc(new RectF(7,3,17,21),300,120,false,paint);break;
            case "search":canvas.drawCircle(10,10,6,paint);canvas.drawLine(14.5f,14.5f,20,20,paint);break;
            case "undo":path(canvas,new float[]{9,7,4,12,9,17});canvas.drawArc(new RectF(4,4,20,20),180,180,false,paint);break;
            case "redo":path(canvas,new float[]{15,7,20,12,15,17});canvas.drawArc(new RectF(4,4,20,20),0,180,false,paint);break;
            case "play":paint.setStyle(Paint.Style.FILL);pathFill(canvas,new float[]{8,5,19,12,8,19});break;
            case "stop":canvas.drawRect(6,6,18,18,paint);break;
            case "save":path(canvas,new float[]{4,4,17,4,20,7,20,20,4,20,4,4});canvas.drawRect(8,4,16,9,paint);canvas.drawRect(7,13,17,20,paint);break;
            case "trash":canvas.drawLine(4,7,20,7,paint);path(canvas,new float[]{6,7,7,21,17,21,18,7});canvas.drawLine(9,7,9.5f,4,paint);canvas.drawLine(15,7,14.5f,4,paint);canvas.drawLine(10,4,14,4,paint);break;
            case "copy":canvas.drawRect(8,3,20,15,paint);canvas.drawRect(4,8,16,21,paint);break;
            case "move":canvas.drawLine(12,4,12,20,paint);canvas.drawLine(4,12,20,12,paint);path(canvas,new float[]{9,6,12,3,15,6});path(canvas,new float[]{9,18,12,21,15,18});path(canvas,new float[]{6,9,3,12,6,15});path(canvas,new float[]{18,9,21,12,18,15});break;
            case "rotate":canvas.drawArc(new RectF(4,4,20,20),40,180,false,paint);path(canvas,new float[]{8,4,13,4,10,9});canvas.drawCircle(12,12,2,paint);break;
            case "scale":canvas.drawRect(3,3,11,11,paint);canvas.drawRect(13,13,21,21,paint);canvas.drawLine(11,11,13,13,paint);break;
            case "more":canvas.drawCircle(12,5,1.6f,paint);canvas.drawCircle(12,12,1.6f,paint);canvas.drawCircle(12,19,1.6f,paint);break;
            case "layers":path(canvas,new float[]{12,3,21,8,12,13,3,8,12,3});path(canvas,new float[]{3,12,12,17,21,12});path(canvas,new float[]{3,16,12,21,21,16});break;
            case "list":path(canvas,new float[]{8,6,21,6});path(canvas,new float[]{8,12,21,12});path(canvas,new float[]{8,18,21,18});canvas.drawCircle(4.5f,6,1.4f,paint);canvas.drawCircle(4.5f,12,1.4f,paint);canvas.drawCircle(4.5f,18,1.4f,paint);break;
            case "logout":path(canvas,new float[]{10,3,4,3,4,21,10,21});canvas.drawLine(9,12,22,12,paint);path(canvas,new float[]{17,7,22,12,17,17});break;
            default:canvas.drawCircle(12,12,9,paint);canvas.drawLine(12,10,12,17,paint);canvas.drawPoint(12,6,paint);
        }canvas.restore();
    }
    void pathFill(Canvas c,float[] a){Path p=new Path();p.moveTo(a[0],a[1]);for(int i=2;i<a.length;i+=2)p.lineTo(a[i],a[i+1]);p.close();c.drawPath(p,paint);}
    void path(Canvas c,float[] a){Path p=new Path();p.moveTo(a[0],a[1]);for(int i=2;i<a.length;i+=2)p.lineTo(a[i],a[i+1]);c.drawPath(p,paint);}
}
