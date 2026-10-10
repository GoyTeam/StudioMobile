package dev.falix.studiomobile;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.File;
/** Fast recapture of final Projects layout; uses the same real native-view capture. */
public class CaptureProjectQa extends NativeQa {
    @Override public void onStart(){Bundle result=new Bundle();try{Context target=getTargetContext();target.getSharedPreferences("settings",0).edit().putBoolean("setupStarted",true).putBoolean("setupDone",false).commit();Intent i=new Intent(target,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);app=(MainActivity)startActivitySync(i);directory=new File(target.getExternalFilesDir(null),"qa");directory.mkdirs();pause(2000);ui(()->app.navigate("projects"));pause(2000);capture("06-projects-empty");finish(Activity.RESULT_OK,result);}catch(Exception e){result.putString("error",e.toString());finish(Activity.RESULT_CANCELED,result);}}
}
