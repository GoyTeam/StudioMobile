package dev.falix.studiomobile;
import android.content.Context;
import android.provider.Settings;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
/** Native motion, automatically honoring disabled system animations and the app's Reduce motion setting. */
final class UiMotion {
    static boolean enabled(Context c){return !c.getSharedPreferences("settings",0).getBoolean("reduceMotion",false)&&Settings.Global.getFloat(c.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0;}
    static void enter(View v){if(v==null||!enabled(v.getContext()))return;v.animate().cancel();v.setAlpha(0f);v.setTranslationY(8*v.getResources().getDisplayMetrics().density);v.animate().alpha(1f).translationY(0).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();}
    static void press(View v){v.setOnTouchListener((target,event)->{if(enabled(target.getContext())){int action=event.getActionMasked();if(action==MotionEvent.ACTION_DOWN)target.animate().scaleX(.975f).scaleY(.975f).setDuration(90).start();else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)target.animate().scaleX(1).scaleY(1).setDuration(140).start();}return false;});}
}
