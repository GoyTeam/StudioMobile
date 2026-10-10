package dev.falix.studiomobile;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;

/** Feature-specific, source-linked permissions guide; optional scopes never grant unimplemented capabilities. */
final class GuideUi {
    static final String KEYS="https://create.roblox.com/docs/cloud/auth/api-keys";
    static final String ASSETS="https://create.roblox.com/docs/cloud/reference/features/assets";
    static final String PLACES="https://create.roblox.com/docs/cloud/reference/features/places";
    static int dp(MainActivity a,float n){return(int)(n*a.getResources().getDisplayMetrics().density+.5f);}
    static void show(MainActivity a){
        if(a.guideWindow!=null&&a.guideWindow.isShowing())return;
        Dialog dialog=new Dialog(a,a.dark?dev.falix.studiomobile.R.style.AppThemeDark:dev.falix.studiomobile.R.style.AppTheme);a.guideWindow=dialog;
        LinearLayout shell=a.vertical();shell.setBackgroundColor(a.WHITE);a.applySafeInsets(shell);
        LinearLayout bar=a.horizontal();bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(a,16),0,dp(a,20),0);
        bar.addView(a.iconAction("arrow","Back to connection",()->dialog.dismiss()),new LinearLayout.LayoutParams(dp(a,44),dp(a,44)));a.marginAdd(bar,a.text("Creator connection guide",18,true,a.INK),8,0);a.spacer(bar);bar.addView(a.badge("CHECKED OCT 10, 2026",a.BLUE,a.SOFT));shell.addView(bar,new LinearLayout.LayoutParams(-1,dp(a,48)));a.divider(shell);
        LinearLayout workspace=a.horizontal();shell.addView(workspace,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout rail=a.vertical();rail.setPadding(dp(a,16),dp(a,20),dp(a,16),dp(a,16));rail.setBackgroundColor(a.CANVAS);workspace.addView(rail,new LinearLayout.LayoutParams(dp(a,198),-1));
        a.addText(rail,"Creator API key",16,true,a.INK,6);a.addText(rail,"Create · scopes · connect",13,false,a.MUTED,18);
        String[] labels={"1  Create a key","2  Required permissions","3  Optional scopes","4  Connect & fix"};
        LinearLayout main=a.vertical();workspace.addView(main,new LinearLayout.LayoutParams(0,-1,1));
        FrameLayout viewport=new FrameLayout(a);main.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=a.horizontal();footer.setPadding(dp(a,24),dp(a,10),dp(a,24),dp(a,10));footer.setGravity(Gravity.CENTER_VERTICAL);main.addView(footer,new LinearLayout.LayoutParams(-1,dp(a,66)));
        final Runnable[] rebuild=new Runnable[1];
        rebuild[0]=()->{a.guideBuilding=true;rail.removeViews(2,rail.getChildCount()-2);for(int n=0;n<4;n++){final int index=n;TextView nav=a.button(labels[n],a.guideTab==n,()->{a.guideTab=index;rebuild[0].run();});nav.setTextSize(12);nav.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);rail.addView(nav,new LinearLayout.LayoutParams(-1,dp(a,44)));a.gap(rail,6);}rail.addView(new View(a),new LinearLayout.LayoutParams(1,0,1));
            viewport.removeAllViews();a.guideBody=a.page(viewport);populate(a,a.guideBody);UiMotion.enter(a.guideBody);
            footer.removeAllViews();if(a.guideTab>0)footer.addView(a.button("Back",false,()->{a.guideTab--;rebuild[0].run();}));else footer.addView(a.button("Official API Keys",false,()->a.openBrowser(KEYS)));a.spacer(footer);footer.addView(a.button(a.guideTab<3?"Next step":"Back to connection",true,()->{if(a.guideTab<3){a.guideTab++;rebuild[0].run();}else dialog.dismiss();}));a.guideBuilding=false;
        };rebuild[0].run();dialog.setContentView(shell);dialog.show();dialog.getWindow().setBackgroundDrawable(new ColorDrawable(a.WHITE));dialog.getWindow().setLayout(-1,-1);a.applySystemBars();
    }
    static LinearLayout card(MainActivity a,LinearLayout p,int bg){LinearLayout c=a.vertical();c.setPadding(dp(a,20),dp(a,18),dp(a,20),dp(a,18));c.setBackground(a.shape(bg,20,true));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(a,14);p.addView(c,lp);return c;}
    static void heading(MainActivity a,LinearLayout p,String step,String title,String sub){p.addView(a.badge(step,a.BLUE,a.SOFT));a.gap(p,8);a.addText(p,title,22,true,a.INK,6);a.addText(p,sub,14,false,a.MUTED,12);}
    static void action(MainActivity a,LinearLayout p,String label,String url){p.addView(a.button(label,false,()->a.openBrowser(url)));a.gap(p,8);}
    static void info(MainActivity a,LinearLayout p,String title,String body){LinearLayout c=card(a,p,a.CANVAS);a.addText(c,title,16,true,a.INK,6);a.addText(c,body,14,false,a.MUTED,0);}
    static void scope(MainActivity a,LinearLayout p,String feature,String api,String operations,String scopes,String use,String url,boolean required){
        LinearLayout c=card(a,p,required?a.SOFT:a.CANVAS);LinearLayout row=a.horizontal();row.setGravity(Gravity.CENTER_VERTICAL);row.addView(a.text(feature,17,true,a.INK),new LinearLayout.LayoutParams(0,-2,1));a.marginAdd(row,a.badge(required?(feature.startsWith("Download")?"FOR DOWNLOAD":"FOR CLOUD UPLOAD"):"OPTIONAL FEATURE",a.BLUE,a.SOFT),8,0);c.addView(row);a.gap(c,10);
        TextView code=a.text(scopes,13,true,a.BLUE);code.setTypeface(android.graphics.Typeface.MONOSPACE);code.setTextIsSelectable(true);code.setPadding(dp(a,12),dp(a,10),dp(a,12),dp(a,10));code.setBackground(a.shape(a.WHITE,12,true));c.addView(code,new LinearLayout.LayoutParams(-1,-2));a.gap(c,8);a.addText(c,"API system   "+api,13,true,a.INK,5);a.addText(c,"Operations   "+operations,13,false,a.MUTED,10);a.addText(c,use,14,false,a.MUTED,10);c.addView(a.button("Verify in official reference",false,()->a.openBrowser(url)));
    }
    static void populate(MainActivity a,LinearLayout p){
        switch(a.guideTab){
            case 0:
                heading(a,p,"SETUP · 1 OF 4","Create your own Creator key","Open Roblox in your browser. Your key connects this tool to your account; it is not a Roblox website session.");
                info(a,p,"01  Open Credentials → API Keys","Sign in at Creator Dashboard. Open Credentials, choose API Keys, then Create API Key. Use a user-owned key for this app.");
                p.addView(a.button("Open Creator API Keys dashboard",true,()->a.openBrowser("https://create.roblox.com/dashboard/credentials?activeTab=ApiKeysTab")));a.gap(p,14);
                info(a,p,"02  Name it and select API systems","Name the key Studio Mobile. Under Access Permissions, select each required API system and its operation. The next two steps show exact system identifiers, operations and scope strings.");
                info(a,p,"03  Restrict access thoughtfully","For APIs that support it, restrict the key to the experiences you manage. Not every scope supports resource restrictions. Group resources still require the key owner's actual group/edit permissions.");
                info(a,p,"04  Security options","An expiry date and IP restrictions are optional. Match IP rules to your connection; mobile IPs can change. Never relax restrictions blindly. Save & Generate key, copy it securely, and confirm Active status. Unused keys can auto-expire after 60 days.");
                action(a,p,"Official key creation & security guide",KEYS);break;
            case 1:
                heading(a,p,"PERMISSIONS · 2 OF 4","Required permissions, clearly","Use an active user-owned key. Downloads and cloud uploads need their corresponding permissions; no universal Studio permission exists.");
                scope(a,p,"Download owned / editable place files","legacy-asset","Manage","legacy-asset:manage","Add the legacy Asset Delivery system and choose Manage. This is a broad Manage scope, not a fictional download-only Read scope. Roblox also checks whether the key owner is authorized to access the place. Endpoint: GET apis.roblox.com/asset-delivery-api/v1/assetId/{placeId}.","https://devforum.roblox.com/t/creator-action-required-new-asset-delivery-api-endpoints-for-community-tools/3574403",true);
                scope(a,p,"Save / publish a linked cloud place","universe-places","Write","universe-places:write","Required only for Save / publish. Select the existing target experience. Saved creates a non-live version; Published updates its place version. It does not create a universe or change visibility, devices or metadata.","https://create.roblox.com/docs/cloud/guides/usage-place-publishing",true);
                info(a,p,"Account connection / owner identity","POST /api-keys/v1/introspect validates the key and returns authorizedUserId. It does not require a separate data-store, publishing, profile or imaginary login scope. This app accepts only an enabled, non-expired user-owned key with an identifiable owner.");
                info(a,p,"Profile, avatar and public experience list","The public Users, Thumbnails and Games requests used here do not need extra API-key scopes. This does not expose every private/group experience. Supplying more write permissions does not turn a public list into a complete private list.");
                info(a,p,"Existing universe metadata","GET /cloud/v2/universes/{id} needs valid authentication and an accessible universe ID. The official reference does not list an additional named scope for this GET. We do not invent a universe:read requirement. A 403 still means you must check key/account access.");
                action(a,p,"Official universe metadata reference","https://create.roblox.com/docs/cloud/reference/features/universes");break;
            case 2:
                heading(a,p,"OPTIONAL · 3 OF 4","Other permissions by feature","These are exact published scopes for specific capabilities—not a request to enable everything. Only Save / publish is implemented here; other scopes do not activate unfinished features.");
                scope(a,p,"Publish a saved .rbxl / .rbxlx","universe-places","Write","universe-places:write","For publishing a new version of an existing place. Select the target experience and Write operation. Requires Universe ID + Place ID and edit/publish access. This is different from updating place metadata.","https://create.roblox.com/docs/cloud/guides/usage-place-publishing",false);
                scope(a,p,"Read asset metadata / versions","asset","Read","asset:read","For retrieving asset metadata, versions and operation results. Not a substitute for legacy-asset:manage when downloading place content.",ASSETS,false);
                scope(a,p,"Upload / update supported assets","asset","Read + Write","asset:read  +  asset:write","For supported asset creation/update endpoints and polling their operation status. Resource ownership and supported asset types still apply.",ASSETS,false);
                scope(a,p,"Read supported engine instances (Beta)","universe.place.instance","Read","universe.place.instance:read","For Get Instance and List Instance Children. The instance API is a limited beta surface, not unrestricted access to Roblox Studio's entire engine.",PLACES,false);
                scope(a,p,"Update supported instance properties (Beta)","universe.place.instance","Read + Write for a read/edit workflow","universe.place.instance:read\nuniverse.place.instance:write","The PATCH requires Write; include Read only when the editor reads before editing. Supported instance/property limitations still apply. The 3D editor is not implemented.",PLACES,false);
                scope(a,p,"Change place name / configuration","universe.place","Write","universe.place:write","For supported Update Place fields. Roblox can reject updates with HTTP 409 while a Team Create session has the place open. This does not publish a binary place file.",PLACES,false);
                scope(a,p,"Read place-version history","universe.place","Read","universe.place:read","For the documented place-version history/contributor endpoints. Experimental availability and key eligibility may vary. This is not a general permission to discover every private experience.",PLACES,false);
                scope(a,p,"Experience settings / server restart","universe","Write","universe:write","For Update Universe and Restart Servers. This can change live experience settings, so isolate it from a download-only key.","https://create.roblox.com/docs/cloud/reference/features/universes",false);
                scope(a,p,"Creator Store search / asset details","creator-store-product","Read","creator-store-product:read","For the authenticated Creator Store search/details API. It does not grant ownership or redistribution rights to results.",ASSETS,false);
                info(a,p,"Optional Roblox account login (OAuth)","OAuth uses a registered app, supported PKCE redirect flow and openid + profile identity scopes. Those are OAuth scopes, not API-key permissions. The client must be configured and reviewed as appropriate; never embed a client secret in an APK.");
                action(a,p,"Official OAuth registration & PKCE guide","https://create.roblox.com/docs/cloud/auth/oauth2-overview");break;
            default:
                heading(a,p,"CONNECT · 4 OF 4","Connect safely. Fix the right problem.","Save & Generate key in Creator Dashboard, paste it in the app, then Connect. Keep the secret out of chat, screenshots and source repositories.");
                info(a,p,"Before you connect","Confirm: user-owned key • Active status • legacy-asset / Manage for downloads • intended experiences selected where supported • owner can edit the target place. The app stores the key encrypted with Android Keystore.");
                info(a,p,"HTTP 400 / 401","400: key format or request parameters may be wrong. Copy the complete key, not its name or ID. 401: recheck active status, expiry and authentication. Do not add unrelated permissions as a fix.");
                info(a,p,"HTTP 403","Check the specific endpoint's scopes, owner/edit/group access, resource restrictions, IP rules and account eligibility. A broad scope cannot override Roblox's ownership checks. If a scope isn't offered to your account, do not substitute unrelated writes.");
                info(a,p,"HTTP 404 / 409 / 429","404: wrong ID, inaccessible or missing resource. Universe IDs and Place IDs are different. 409: conflicting state, such as an active Team Create session. 429: rate limit; wait before retrying.");
                info(a,p,"What a download includes","A place download imports one .rbxl/.rbxlx file, not every external dependency or a complete runnable Roblox engine. Private/group discovery can remain incomplete. Logout removes local credentials; revoke the key separately in Creator Dashboard.");
                action(a,p,"Open Creator Dashboard to inspect your key",KEYS);break;
        }
    }
}
