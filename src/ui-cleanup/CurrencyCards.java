package local.simcity.offline;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Local wallet controls; native calls queue changes on the game's thread. */
public final class CurrencyCards {
    public static FrameLayout create(final Activity activity) {
        final FrameLayout panel = new FrameLayout(activity);
        panel.setClickable(true);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER);
        panel.addView(row, new FrameLayout.LayoutParams(-1, -1));
        for (int i=0; i<2; i++) {
            final int type=i;
            final LinearLayout card=new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xff72c9e5, 0xff174c6c});
            background.setCornerRadius(24);
            background.setStroke(3, 0xffa8e3f2);
            card.setBackground(background);
            card.setPadding(16,16,16,20);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-1,1);
            cp.setMargins(20,24,20,24);
            row.addView(card,cp);
            ImageView icon=new ImageView(activity);
            icon.setImageDrawable(new CurrencyIcon(type));
            icon.setContentDescription(type==0 ? "增加1000模拟币" : "增加1000绿钞");
            card.addView(icon,new LinearLayout.LayoutParams(-1,0,1));
            TextView label=new TextView(activity);
            label.setText(type==0 ? "模拟币 +1,000" : "绿钞 +1,000");
            label.setTextColor(Color.WHITE);label.setTextSize(24);
            label.setGravity(Gravity.CENTER);
            card.addView(label,new LinearLayout.LayoutParams(-1,-2));
            card.setContentDescription(icon.getContentDescription());
            card.setOnTouchListener(new View.OnTouchListener(){public boolean onTouch(View v,MotionEvent event){
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
                    v.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.72f).setDuration(65).start();
                }else if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(110).start();
                }return false;
            }});
            card.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
                if(!UiCleaner.addCurrency(type)) Toast.makeText(activity,"请稍后重试",Toast.LENGTH_SHORT).show();
            }});
        }
        panel.setVisibility(View.GONE);
        return panel;
    }
    private static final class CurrencyIcon extends Drawable {
        private final int type;private final Paint paint=new Paint(3);
        CurrencyIcon(int type){this.type=type;}
        public void draw(Canvas canvas){
            canvas.save(); android.graphics.Rect b=getBounds();
            float scale=Math.min(b.width()/240f,b.height()/220f);
            canvas.translate(b.centerX()-120*scale,b.centerY()-110*scale);canvas.scale(scale,scale);
            if(type==0){
                paint.setColor(0xffb76e13);canvas.drawOval(35,30,205,200,paint);
                paint.setShader(new LinearGradient(30,25,190,180,0xffffed90,0xffe8a720,Shader.TileMode.CLAMP));
                canvas.drawCircle(115,105,80,paint);paint.setShader(null);
                paint.setColor(0xffb77b1d);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(7);canvas.drawCircle(115,105,64,paint);
                paint.setStyle(Paint.Style.FILL);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(75);canvas.drawText("§",91,131,paint);
            }else{
                for(int i=2;i>=0;i--){canvas.save();canvas.translate(i*8,-i*12);canvas.rotate(-12,112,110);
                    paint.setColor(0xff35733e);canvas.drawRoundRect(27,75,205,170,10,10,paint);
                    paint.setShader(new LinearGradient(30,75,190,165,0xffc4ed8e,0xff67b659,Shader.TileMode.CLAMP));
                    canvas.drawRoundRect(23,65,201,155,10,10,paint);paint.setShader(null);
                    paint.setColor(0xff4c914e);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(4);canvas.drawRoundRect(34,76,190,145,5,5,paint);
                    paint.setStyle(Paint.Style.FILL);canvas.drawCircle(112,110,28,paint);paint.setColor(0xffd9f4a6);
                    paint.setTextSize(38);paint.setTypeface(Typeface.DEFAULT_BOLD);canvas.drawText("§",101,124,paint);canvas.restore();}
            }canvas.restore();
        }
        public void setAlpha(int value){paint.setAlpha(value);}
        public void setColorFilter(ColorFilter value){paint.setColorFilter(value);}
        public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}

