package dev.falix.studiomobile;
import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.Bitmap;
import android.view.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Test-only instrumentation, not included in the deliverable APK. No real credentials. */
public class NativeQa extends Instrumentation {
    String prefix="";MainActivity app;final ArrayList<String> report=new ArrayList<>();File directory;
    public void onCreate(Bundle args){super.onCreate(args);prefix=args==null?"":args.getString("prefix","");start();}
    void assertTrue(boolean condition,String name){if(!condition)throw new AssertionError(name);report.add("PASS: "+name);}
    void ui(Runnable action){runOnMainSync(action);waitForIdleSync();}
    void shell(String command)throws Exception{try(android.os.ParcelFileDescriptor p=getUiAutomation().executeShellCommand(command);InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(p)){byte[] b=new byte[1024];while(in.read(b)!=-1){}}}
    void back(){getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK),true);getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK),true);}
    boolean clickText(String value){android.view.accessibility.AccessibilityNodeInfo r=getUiAutomation().getRootInActiveWindow();if(r==null)return false;for(android.view.accessibility.AccessibilityNodeInfo n:r.findAccessibilityNodeInfosByText(value))if(value.equals(String.valueOf(n.getText())))return n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);return false;}
    void pause(long millis){try{Thread.sleep(millis);}catch(Exception ignored){}}
    void capture(String name)throws Exception{
        // Only QA clears FLAG_SECURE on credential UI; the actual APK protects real keys.
        ui(()->app.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));pause(1200);
        // Capture the actual laid-out native app view, excluding unrelated emulator system windows.
        // No account data or UI is mocked; these are app-only native view captures.
        final Bitmap[] captured=new Bitmap[1];
        ui(()->{View view=app.guideWindow!=null&&app.guideWindow.isShowing()?app.guideWindow.getWindow().getDecorView():app.appearanceWindow!=null&&app.appearanceWindow.isShowing()?app.appearanceWindow.getWindow().getDecorView():app.activeDialog!=null&&app.activeDialog.isShowing()?app.activeDialog.getWindow().getDecorView():app.root;
            captured[0]=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas=new android.graphics.Canvas(captured[0]);canvas.drawColor(app.WHITE);view.draw(canvas);});
        Bitmap image=captured[0];
        try(OutputStream out=new FileOutputStream(new File(directory,prefix+name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
    }
    public void onStart(){Bundle result=new Bundle();try{
        Context target=getTargetContext();target.getSharedPreferences("settings",0).edit().clear().putBoolean("setupStarted",true).commit();
        new CredentialVault(target).clear();
        Intent launch=new Intent(target,MainActivity.class);launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);app=(MainActivity)startActivitySync(launch);
        directory=new File(target.getExternalFilesDir(null),"qa");directory.mkdirs();
        pause(2500);
        if(Build.VERSION.SDK_INT>=30){
            ui(()->app.requestStorage());pause(1800);
            android.view.accessibility.AccessibilityNodeInfo settings=getUiAutomation().getRootInActiveWindow();
            assertTrue(settings!=null&&String.valueOf(settings.getPackageName()).contains("settings"),"All-files request opens Android Settings");
            capture("qa-all-files-settings");shell("appops set --uid dev.falix.studiomobile MANAGE_EXTERNAL_STORAGE allow");back();pause(1500);
            assertTrue(new ProjectStore(target).fullAccess(),"Android all-files grant is recognized");
            if(Build.VERSION.SDK_INT>=33){
                capture("qa-notification-permission");assertTrue(clickText("Don't allow"),"Native notification prompt can be declined");pause(1000);
                assertTrue(!app.notificationAllowed(),"Notification denial does not grant permission or block setup");
                ui(()->app.requestNotifications());pause(1200);assertTrue(clickText("Allow"),"Native notification permission can be granted");pause(1000);
                assertTrue(app.notificationAllowed(),"Notification grant is recognized");
            }
        }
        capture("01-setup");
        ui(()->{app.screen="login";app.render();});capture("02-api-key-connection");
        ui(()->app.login());assertTrue(app.session==null&&!app.loginError.isEmpty(),"Empty key does not authenticate");
        ui(()->{app.keyInput.setText("deliberately-invalid-test-key");app.login();});
        for(int i=0;i<50;i++){pause(500);if(!app.busy)break;}
        assertTrue(!app.busy&&app.session==null&&app.loginError.contains("HTTP 400"),"Roblox rejects invalid key with HTTP 400 without creating a session");capture("03-rejected-key");
        ui(()->{app.loginError="";app.render();app.guide();});capture("04-key-guide");
        ui(()->{if(app.guideWindow!=null)app.guideWindow.dismiss();});pause(1000);
        // Cryptography and local import tests use fixtures only, removed before user-facing captures.
        CredentialVault vault=new CredentialVault(target);vault.save("qa-fixture-not-a-real-api-key");assertTrue("qa-fixture-not-a-real-api-key".equals(vault.load()),"Android Keystore round-trip succeeds");
        String cipher=target.getSharedPreferences("credentials",0).getString("cipher","");assertTrue(!cipher.contains("qa-fixture"),"Preferences contain ciphertext, not the API key");
        File xml=new File(target.getCacheDir(),"qa-source.rbxlx");try(FileOutputStream out=new FileOutputStream(xml)){out.write("<roblox version=\"4\"></roblox>".getBytes("UTF-8"));}
        assertTrue(RobloxApi.validPlace(xml)&&RobloxApi.xmlPlace(xml),"XML place signature recognized");
        File binary=new File(target.getCacheDir(),"qa-binary.rbxl");try(FileOutputStream out=new FileOutputStream(binary)){out.write(new byte[]{60,114,111,98,108,111,120,33,(byte)137,(byte)255,13,10,26,10,0,0});}
        assertTrue(RobloxApi.validPlace(binary)&&!RobloxApi.xmlPlace(binary),"Binary place signature recognized without claiming full format validation");
        File invalid=new File(target.getCacheDir(),"qa-error.txt");try(FileOutputStream out=new FileOutputStream(invalid)){out.write("{\"error\":\"no access\"}".getBytes("UTF-8"));}assertTrue(!RobloxApi.validPlace(invalid),"JSON error is not accepted as a place file");
        ProjectStore store=new ProjectStore(target);assertTrue(store.available(),"Test device storage access granted");
        android.net.Uri imported=store.importFile(xml,"QA-fixture",true);assertTrue(new File(imported.getPath()).exists(),"Local import writes an actual place file");
        boolean found=false;for(ProjectStore.Project p:store.scan())if(p.uri.equals(imported))found=true;assertTrue(found,"Project scan finds imported place file");
        // Session fixture tests logout lifecycle only; never presented as live Roblox authentication.
        JSONObject info=new JSONObject().put("enabled",true).put("expired",false).put("authorizedUserId",1).put("scopes",new JSONArray());
        RobloxApi.Session fixture=new RobloxApi.Session("qa-fixture",1,new JSONObject().put("name","TEST ONLY").put("displayName","TEST ONLY"),"",info);
        ui(()->{app.session=fixture;app.logout();});assertTrue(vault.load()==null&&app.session==null,"Logout deletes credential material and session state");assertTrue(new File(imported.getPath()).exists(),"Logout preserves local project files");
        new File(imported.getPath()).delete();xml.delete();binary.delete();invalid.delete();
        ui(()->app.navigate("home"));pause(2500);capture("05-home-local-workspace");
        ui(()->app.navigate("projects"));pause(2000);capture("06-projects-empty");
        ui(()->app.navigate("cloud"));capture("07-roblox-connection-required");
        ui(()->app.navigate("shared"));capture("08-shared-coming-soon");
        report.add("CAPTURES: actual native app views, excluding emulator-system windows; not simulated UI or live-account screenshots.");
        report.add("DEVICE: Android "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT);
        report.add("NOT VERIFIED: successful live-key connection, private/group permissions, live authorized download"+(Build.VERSION.SDK_INT<30?", Android 11+ all-files flow":"")+(Build.VERSION.SDK_INT<33?", Android 13+ notifications":""));
        try(PrintWriter out=new PrintWriter(new File(directory,"qa-report.txt"))){for(String line:report)out.println(line);}
        result.putString("report",String.join("\n",report));result.putString("screenshots",directory.getAbsolutePath());finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("error",e.toString());try{if(directory!=null){try(PrintWriter out=new PrintWriter(new File(directory,"qa-error.txt"))){e.printStackTrace(out);}}}catch(Exception ignored){}finish(Activity.RESULT_CANCELED,result);}}
}
