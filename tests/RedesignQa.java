package dev.falix.studiomobile;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;

/** Real native-view captures in both palettes; no invented account or cloud projects. */
public final class RedesignQa extends NativeQa {
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context target=getTargetContext();target.getSharedPreferences("settings",0).edit().putBoolean("setupStarted",true).putBoolean("setupDone",false).putString("appearance","light").putBoolean("reduceMotion",true).commit();new CredentialVault(target).clear();
        app=(MainActivity)startActivitySync(new Intent(target,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));directory=new File(target.getExternalFilesDir(null),"qa020");directory.mkdirs();pause(1800);
        assertTrue(Class.forName("okhttp3.OkHttpClient")!=null,"OkHttp networking dependency is bundled");assertTrue(Class.forName("okhttp3.WebSocket")!=null,"WebSocket foundation is bundled");
        for(String mode:new String[]{"light","dark"}){
            ui(()->app.setAppearance(mode));assertTrue(app.dark==mode.equals("dark"),"Palette switches to "+mode);
            for(String route:new String[]{"setup","login","home","projects","cloud","shared"}){ui(()->app.navigate(route));pause(900);capture(mode+"-"+route);}
            ui(()->app.navigate("login"));for(int tab=0;tab<4;tab++){final int n=tab;ui(()->{app.guideTab=n;app.guide();});pause(400);capture(mode+"-guide-"+tab);assertTrue(app.guideBody!=null,"Guide tab "+tab+" renders in "+mode);if(tab==1||tab==2){ui(()->((android.widget.ScrollView)app.guideBody.getParent()).scrollTo(0,app.guideBody.getHeight()>0?180:0));capture(mode+"-guide-"+tab+"-details");}ui(()->app.guideWindow.dismiss());}
            ui(()->app.navigate("projects"));ui(()->{app.projectQuery="not-an-existing-project";app.render();});capture(mode+"-project-search");assertTrue(app.projectResults!=null,"Projects search renders real empty results in "+mode);ui(()->{app.projectQuery="";app.projectFilter="all";});
            ui(()->app.appearance());pause(500);
            capture(mode+"-appearance");ui(()->app.appearanceWindow.dismiss());
            ui(()->app.message("Theme preview","Native dialogs follow the selected appearance, including their text and action buttons."));capture(mode+"-dialog");ui(()->app.activeDialog.dismiss());
            ui(()->app.navigate("login"));
        }
        ui(()->{app.setAppearance("dark");app.navigate("login");app.keyInput.setText("deliberately-invalid-test-key");app.login();});for(int n=0;n<125&&app.busy;n++)pause(500);
        assertTrue(!app.busy&&app.session==null&&app.loginError.contains("HTTP 400"),"Bundled OkHttp transport receives actual Roblox invalid-key rejection");capture("dark-rejected-key");
        ui(()->{app.prefs.edit().putBoolean("reduceMotion",false).commit();app.navigate("home");});pause(600);assertTrue(app.content.getAlpha()==1f,"Native transition finishes without obscuring content");
        ui(()->app.prefs.edit().putBoolean("reduceMotion",true).commit());assertTrue(!UiMotion.enabled(app),"Reduce motion setting disables transitions");
        // Export the actual Android vector drawable for icon inspection.
        final android.graphics.Bitmap[] image=new android.graphics.Bitmap[1];ui(()->{image[0]=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888);android.graphics.drawable.Drawable icon=app.getDrawable(R.drawable.ic_app);icon.setBounds(0,0,256,256);icon.draw(new android.graphics.Canvas(image[0]));});try(OutputStream out=new FileOutputStream(new File(directory,"app-icon.png"))){image[0].compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}image[0].recycle();
        report.add("DEVICE: Android "+Build.VERSION.RELEASE+" API "+Build.VERSION.SDK_INT);report.add("CAPTURES: actual native app views, not mockups or live-account screenshots.");report.add("NOT VERIFIED: valid creator-key sign-in, authorized place download, publishing/editor/collaboration features.");try(PrintWriter out=new PrintWriter(new File(directory,"redesign-report.txt"))){for(String line:report)out.println(line);}result.putString("report",String.join("\n",report));finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("error",e.toString());finish(Activity.RESULT_CANCELED,result);}}
}
