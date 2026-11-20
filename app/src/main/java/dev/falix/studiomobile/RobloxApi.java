package dev.falix.studiomobile;

import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** No cookies, passwords, backend, or credential-bearing redirects. */
public final class RobloxApi {
    public static final String ROOT = "https://apis.roblox.com";
    public static final long MAX_DOWNLOAD = 100L * 1024 * 1024;
    public static final class ApiException extends IOException {
        public final int status;
        public ApiException(int status, String message) { super(message); this.status = status; }
    }
    public static final class ToolboxModel {
        public final long assetId;
        public final String name;
        public ToolboxModel(long assetId, String name) { this.assetId = assetId; this.name = name; }
    }
    public static final class Session {
        public final String key, cookie, username, displayName, avatar;
        public final long userId;
        public final JSONObject introspection;
        Session(String k, long id, JSONObject profile, String image, JSONObject info) {
            this(k, null, id, profile, image, info);
        }
        Session(String k, String c, long id, JSONObject profile, String image, JSONObject info) {
            key=k; cookie=c; userId=id; username=profile.optString("name"); displayName=profile.optString("displayName",username); avatar=image; introspection=info==null?new JSONObject():info;
        }
        public boolean hasCookie() { return cookie != null && !cookie.isEmpty(); }
        public boolean canDownload() {
            if (hasCookie()) return true;
            JSONArray scopes=introspection.optJSONArray("scopes");
            if(scopes!=null) for(int i=0;i<scopes.length();i++) {
                JSONObject s=scopes.optJSONObject(i);
                if(s!=null && "legacy-asset".equals(s.optString("name"))) {
                    JSONArray ops=s.optJSONArray("operations");
                    if(ops!=null) for(int j=0;j<ops.length();j++) if("manage".equals(ops.optString(j))) return true;
                }
                if(s!=null && "legacy-asset:manage".equals(s.optString("name"))) return true;
            }
            return false;
        }
    }
    public static final class Experience {
        public long id, rootPlace;
        public String name, visibility="Unknown", icon="", iconState="Unavailable";
        public boolean cloudVerified;
        public Experience(long id,String name,long root){this.id=id;this.name=name;rootPlace=root;}
    }
    public static final class Listing {
        public final ArrayList<Experience> items=new ArrayList<>();
        public final ArrayList<String> warnings=new ArrayList<>();
    }
    private static String enc(String s) throws IOException { return URLEncoder.encode(s,"UTF-8"); }
    private HttpURLConnection connection(String url,String key,String method) throws IOException {
        URL target=new URL(url);
        if(!"https".equals(target.getProtocol())) throw new IOException("Only HTTPS is allowed.");
        if(key!=null && !"apis.roblox.com".equals(target.getHost())) throw new IOException("Credential destination blocked.");
        HttpURLConnection c=(HttpURLConnection)target.openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(25000);c.setRequestMethod(method);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent","StudioMobile-Unofficial/0.3.0");
        if(key!=null)c.setRequestProperty("x-api-key",key);
        return c;
    }
    private byte[] read(InputStream in,long limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;long total=0;
        while((n=in.read(b))!=-1){total+=n;if(total>limit)throw new IOException("Response exceeds safety limit.");out.write(b,0,n);}return out.toByteArray();
    }
    public JSONObject json(String url,String key,JSONObject body) throws IOException,JSONException {
        URL target=new URL(url);
        if(!"https".equals(target.getProtocol()))throw new IOException("Only HTTPS is allowed.");
        if(key!=null&&!"apis.roblox.com".equals(target.getHost()))throw new IOException("Credential destination blocked.");
        if(body!=null&&body.has("apiKey")&&!"apis.roblox.com".equals(target.getHost()))throw new IOException("Credential destination blocked.");
        okhttp3.Request.Builder request=new okhttp3.Request.Builder().url(url).header("Accept","application/json").header("User-Agent","StudioMobile-Unofficial/0.3.0");
        if(key!=null)request.header("x-api-key",key);
        if(body!=null)request.post(okhttp3.RequestBody.create(body.toString(),okhttp3.MediaType.get("application/json; charset=utf-8")));
        try(okhttp3.Response response=NetworkRuntime.client().newCall(request.build()).execute()){
            if(!response.isSuccessful())throw error(response.code());
            if(response.body()==null)throw new IOException("Roblox returned an empty response.");
            try(InputStream in=response.body().byteStream()){return new JSONObject(new String(read(in,4*1024*1024),"UTF-8"));}
        }
    }

    private ApiException error(int code) {
        String text="Roblox returned HTTP "+code+".";
        if(code==400)text="Roblox rejected the request. Check the key format or resource ID (HTTP 400).";
        if(code==401)text="Authentication failed. Reconnect with an active API key (HTTP 401).";
        if(code==403)text="Access denied. Check key permissions, experience access, IP restrictions and account eligibility (HTTP 403).";
        if(code==404)text="The resource was not found or is not visible to this account (HTTP 404).";
        if(code==429)text="Roblox rate limit reached. Wait before retrying (HTTP 429).";
        return new ApiException(code,text);
    }
    public Session login(String key) throws Exception {
        JSONObject info=json(ROOT+"/api-keys/v1/introspect",null,new JSONObject().put("apiKey",key));
        long id=info.optLong("authorizedUserId",0);
        if(!info.optBoolean("enabled",false)||info.optBoolean("expired",true)||id<=0)throw new IOException("This key is disabled, expired, or has no identifiable user owner. Use an active user-owned API key.");
        JSONObject user=json("https://users.roblox.com/v1/users/"+id,null,null);
        String image="";
        try {JSONArray a=json("https://thumbnails.roblox.com/v1/users/avatar-headshot?userIds="+id+"&size=150x150&format=Png&isCircular=false",null,null).optJSONArray("data");
            if(a!=null&&a.length()>0&&"Completed".equals(a.getJSONObject(0).optString("state")))image=a.getJSONObject(0).optString("imageUrl");
        } catch(Exception ignored) {} // Avatar failure never invents a person or prevents authentication.
        return new Session(key,id,user,image,info);
    }
    public JSONObject jsonWithCookie(String url, String cookie) throws IOException, JSONException {
        URL target = new URL(url);
        if (!"https".equals(target.getProtocol()) || !imageHost(target)) throw new IOException("Untrusted Roblox host.");
        okhttp3.Request.Builder req = new okhttp3.Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "Mozilla/5.0");
        if (cookie != null && !cookie.isEmpty()) req.header("Cookie", ".ROBLOSECURITY=" + cookie);
        try (okhttp3.Response res = NetworkRuntime.client().newCall(req.build()).execute()) {
            if (!res.isSuccessful()) throw error(res.code());
            if (res.body() == null) throw new IOException("Empty response.");
            try (InputStream in = res.body().byteStream()) { return new JSONObject(new String(read(in, 4 * 1024 * 1024), "UTF-8")); }
        }
    }
    public Session loginWithCookie(String cookie, String optionalApiKey) throws Exception {
        if (cookie == null || cookie.trim().isEmpty()) throw new IOException("Missing Roblox session cookie.");
        JSONObject user = jsonWithCookie("https://users.roblox.com/v1/users/authenticated", cookie.trim());
        long id = user.getLong("id");
        String image = "";
        try {
            JSONArray thumbs = json("https://thumbnails.roblox.com/v1/users/avatar-headshot?userIds=" + id + "&size=150x150&format=Png&isCircular=true", null, null).optJSONArray("data");
            if (thumbs != null && thumbs.length() > 0 && "Completed".equals(thumbs.getJSONObject(0).optString("state"))) image = thumbs.getJSONObject(0).optString("imageUrl", "");
        } catch (Exception ignored) { }
        JSONObject info = new JSONObject();
        if (optionalApiKey != null && !optionalApiKey.trim().isEmpty()) {
            try { info = json(ROOT + "/api-keys/v1/introspect", null, new JSONObject().put("apiKey", optionalApiKey.trim())); } catch (Exception ignored) { }
        }
        return new Session(optionalApiKey == null ? "" : optionalApiKey.trim(), cookie.trim(), id, user, image, info);
    }
    public ArrayList<ToolboxModel> searchToolboxModels(String keyword, String cookie) throws Exception {
        ArrayList<ToolboxModel> out = new ArrayList<>();
        String q = enc(keyword == null ? "" : keyword.trim());
        JSONObject res = jsonWithCookie("https://apis.roblox.com/toolbox-service/v1/marketplace/10?keyword=" + q + "&num=14", cookie);
        JSONArray data = res.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.getJSONObject(i);
                long id = o.optLong("id", 0);
                if (id > 0) out.add(new ToolboxModel(id, o.optString("name", "Model " + id)));
            }
        }
        return out;
    }
    public File downloadAssetWithAuth(long assetId, String apiKey, String cookie, File directory) throws Exception {
        File tmp = File.createTempFile("rbx-asset-", ".rbxm", directory);
        try {
            if (cookie != null && !cookie.isEmpty()) {
                String cur = "https://assetdelivery.roblox.com/v1/asset/?id=" + assetId;
                for (int r = 0; r < 6; r++) {
                    URL u = new URL(cur);
                    if (!trustedAssetUrl(u)) throw new IOException("Untrusted redirect.");
                    okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(cur).header("User-Agent", "Roblox/WinInet");
                    if (u.getHost().toLowerCase(Locale.US).endsWith(".roblox.com")) rb.header("Cookie", ".ROBLOSECURITY=" + cookie);
                    try (okhttp3.Response res = NetworkRuntime.client().newCall(rb.build()).execute()) {
                        if (res.code() >= 300 && res.code() < 400) {
                            String next = res.header("Location");
                            if (next == null) throw new IOException("Missing redirect.");
                            cur = new URL(u, next).toString();
                            continue;
                        }
                        if (!res.isSuccessful() || res.body() == null) throw error(res.code());
                        try (InputStream in = res.body().byteStream()) { copy(in, tmp); }
                        return tmp;
                    }
                }
            }
            if (apiKey != null && !apiKey.isEmpty()) {
                JSONObject o = json(ROOT + "/asset-delivery-api/v1/assetId/" + assetId, apiKey, null);
                String loc = o.optString("location", "");
                if (loc.isEmpty()) {
                    JSONArray locs = o.optJSONArray("locations");
                    if (locs != null && locs.length() > 0) loc = locs.getJSONObject(0).optString("location", "");
                }
                if (!loc.isEmpty()) { fetchAsset(loc, tmp); return tmp; }
            }
            throw new IOException("Sign in with Roblox Web or add an Open Cloud API key to download assets.");
        } catch (Exception e) { tmp.delete(); throw e; }
    }
    private void collectUniverseIds(Object node,Set<Long> ids) {
        if(node instanceof JSONObject){JSONObject o=(JSONObject)node;Iterator<String> keys=o.keys();while(keys.hasNext()){
            String k=keys.next();Object v=o.opt(k);
            if("universeId".equals(k)||"targetId".equals(k)){try{long id=Long.parseLong(String.valueOf(v));if(id>0)ids.add(id);}catch(Exception ignored){}}
            else if("universeIds".equals(k)&&v instanceof JSONArray){JSONArray a=(JSONArray)v;for(int i=0;i<a.length();i++)try{long id=Long.parseLong(a.optString(i));if(id>0)ids.add(id);}catch(Exception ignored){}}
            else if(v instanceof String){
                String s=(String)v;
                int idx=s.indexOf("universes/");
                if(idx>=0){
                    String rest=s.substring(idx+10);
                    int end=0;while(end<rest.length()&&Character.isDigit(rest.charAt(end)))end++;
                    if(end>0)try{long id=Long.parseLong(rest.substring(0,end));if(id>0)ids.add(id);}catch(Exception ignored){}
                }
            }
            else if(v instanceof JSONObject||v instanceof JSONArray)collectUniverseIds(v,ids);
        }} else if(node instanceof JSONArray){JSONArray a=(JSONArray)node;for(int i=0;i<a.length();i++)collectUniverseIds(a.opt(i),ids);}
    }
    public Experience universe(Session s,long id) throws Exception {
        JSONObject o=json(ROOT+"/cloud/v2/universes/"+id,s.key,null);
        Experience e=new Experience(id,o.optString("displayName","Universe "+id),0);e.cloudVerified=true;
        String v=o.optString("visibility");e.visibility="PUBLIC".equals(v)?"Public":"PRIVATE".equals(v)?"Private":"Unknown";
        String root=o.optString("rootPlace");try{e.rootPlace=Long.parseLong(root.substring(root.lastIndexOf('/')+1));}catch(Exception ignored){}
        if(e.rootPlace==0)try{e.rootPlace=json("https://develop.roblox.com/v1/universes/"+id,null,null).optLong("rootPlaceId",0);}catch(Exception ignored){}
        resolveIcon(e);return e;
    }
    public Listing list(Session s,Set<Long> manual) {
        Listing result=new Listing();LinkedHashMap<Long,Experience> map=new LinkedHashMap<>();String cursor="";
        Set<String> cursors=new HashSet<>();
        // 0. Authenticated Roblox Web Session: discovers ALL user & group experiences (Public + Private + Unlisted!)
        if (s.hasCookie()) {
            try {
                String uCursor = "";
                Set<String> uSeen = new HashSet<>();
                do {
                    JSONObject page = jsonWithCookie("https://develop.roblox.com/v1/user/universes?isArchived=false&limit=50&sortOrder=Desc" + (uCursor.isEmpty() ? "" : "&cursor=" + enc(uCursor)), s.cookie);
                    JSONArray data = page.optJSONArray("data");
                    if (data == null) break;
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject o = data.getJSONObject(i);
                        long uid = o.optLong("id", 0);
                        if (uid <= 0) continue;
                        Experience e = new Experience(uid, o.optString("name", "Experience " + uid), o.optLong("rootPlaceId", 0));
                        e.visibility = "Public".equalsIgnoreCase(o.optString("privacyType", "")) ? "Public" : "Private";
                        e.cloudVerified = true;
                        map.put(e.id, e);
                    }
                    uCursor = page.optString("nextPageCursor", "");
                    if ("null".equals(uCursor)) uCursor = "";
                    if (!uCursor.isEmpty() && !uSeen.add(uCursor)) break;
                } while (!uCursor.isEmpty());
            } catch (Exception ignored) { }
            try {
                JSONArray grps = jsonWithCookie("https://develop.roblox.com/v1/user/groups/canmanage", s.cookie).optJSONArray("data");
                if (grps != null) {
                    for (int i = 0; i < grps.length() && i < 15; i++) {
                        JSONObject g = grps.getJSONObject(i);
                        long gid = g.optLong("id", 0);
                        String gname = g.optString("name", "Group");
                        if (gid <= 0) continue;
                        try {
                            JSONArray gu = jsonWithCookie("https://develop.roblox.com/v1/groups/" + gid + "/universes?isArchived=false&limit=50&sortOrder=Desc", s.cookie).optJSONArray("data");
                            if (gu != null) for (int j = 0; j < gu.length(); j++) {
                                JSONObject o = gu.getJSONObject(j);
                                long uid = o.optLong("id", 0);
                                if (uid > 0 && !map.containsKey(uid)) {
                                    Experience e = new Experience(uid, o.optString("name") + " · " + gname, o.optLong("rootPlaceId", 0));
                                    e.visibility = "Group";
                                    e.cloudVerified = true;
                                    map.put(e.id, e);
                                }
                            }
                        } catch (Exception ignored) { }
                    }
                }
            } catch (Exception ignored) { }
        }
        // 1. Studio Start Page universe search (discovers Public AND Private user experiences without Universe ID)
        try {
            String sCursor = "";
            Set<String> sSeen = new HashSet<>();
            do {
                String url = ROOT + "/universes/v1/search?isTemplates=false&isArchived=false&creatorTargetId=" + s.userId
                        + "&creatorType=User&limit=50&sortOrder=Desc" + (sCursor.isEmpty() ? "" : "&cursor=" + enc(sCursor));
                JSONObject page = json(url, s.key, null);
                JSONArray data = page.optJSONArray("data");
                if (data == null) break;
                for (int i = 0; i < data.length(); i++) {
                    JSONObject o = data.getJSONObject(i);
                    long uid = o.optLong("id", 0);
                    if (uid <= 0) continue;
                    Experience e = new Experience(uid, o.optString("name", "Experience " + uid), o.optLong("rootPlaceId", 0));
                    String priv = o.optString("privacyType", "");
                    e.visibility = "Public".equalsIgnoreCase(priv) ? "Public" : ("Private".equalsIgnoreCase(priv) ? "Private" : "Cloud");
                    e.cloudVerified = true;
                    map.put(e.id, e);
                }
                sCursor = page.optString("nextPageCursor", "");
                if ("null".equals(sCursor)) sCursor = "";
                if (!sCursor.isEmpty() && !sSeen.add(sCursor)) break;
            } while (!sCursor.isEmpty());
        } catch (Exception ignored) { }
        // 2. Public & accessible user games via games.roblox.com v2
        try {
            do {
                JSONObject page=json("https://games.roblox.com/v2/users/"+s.userId+"/games?accessFilter=2&limit=50&sortOrder=Desc"+(cursor.isEmpty()?"":"&cursor="+enc(cursor)),null,null);
                JSONArray data=page.optJSONArray("data");if(data==null)throw new IOException("Unexpected listing response.");
                for(int i=0;i<data.length();i++){JSONObject o=data.getJSONObject(i);JSONObject root=o.optJSONObject("rootPlace");Experience e=new Experience(o.getLong("id"),o.optString("name"),root==null?0:root.optLong("id",0));e.visibility="Public";map.put(e.id,e);}
                cursor=page.optString("nextPageCursor","");if("null".equals(cursor))cursor="";
                if(!cursor.isEmpty()&&!cursors.add(cursor))throw new IOException("Roblox returned a repeated pagination cursor.");
            } while(!cursor.isEmpty());
        } catch(Exception e){result.warnings.add("Public listing incomplete: "+safe(e));}
        // Automatically discover group experiences the user belongs to (owner / dev roles first)
        try {
            JSONArray groups=json("https://groups.roblox.com/v1/users/"+s.userId+"/groups/roles",null,null).optJSONArray("data");
            if(groups!=null){
                int fetchedGroups=0;
                for(int i=0;i<groups.length()&&fetchedGroups<12;i++){
                    JSONObject entry=groups.optJSONObject(i);
                    if(entry==null)continue;
                    JSONObject grp=entry.optJSONObject("group");
                    JSONObject role=entry.optJSONObject("role");
                    if(grp==null)continue;
                    long gid=grp.optLong("id",0);
                    int rank=role==null?0:role.optInt("rank",0);
                    if(gid<=0||rank<10)continue;
                    String gname=grp.optString("name","Group");
                    fetchedGroups++;
                    try {
                        try {
                            JSONObject sg = json(ROOT + "/universes/v1/search?isTemplates=false&isArchived=false&creatorTargetId=" + gid + "&creatorType=Group&limit=50&sortOrder=Desc", s.key, null);
                            JSONArray sgd = sg.optJSONArray("data");
                            if (sgd != null) for (int j = 0; j < sgd.length(); j++) {
                                JSONObject o = sgd.getJSONObject(j);
                                long uid = o.optLong("id", 0);
                                if (uid > 0 && !map.containsKey(uid)) {
                                    Experience e = new Experience(uid, o.optString("name") + " · " + gname, o.optLong("rootPlaceId", 0));
                                    e.visibility = "Group";
                                    e.cloudVerified = true;
                                    map.put(e.id, e);
                                }
                            }
                        } catch (Exception ignored) { }
                        JSONObject gp=json("https://games.roblox.com/v2/groups/"+gid+"/games?accessFilter=2&limit=50&sortOrder=Desc",null,null);
                        JSONArray gdata=gp.optJSONArray("data");
                        if(gdata!=null)for(int j=0;j<gdata.length();j++){
                            JSONObject o=gdata.getJSONObject(j);
                            JSONObject root=o.optJSONObject("rootPlace");
                            long uid=o.getLong("id");
                            if(!map.containsKey(uid)){
                                Experience e=new Experience(uid,o.optString("name")+" · "+gname,root==null?0:root.optLong("id",0));
                                e.visibility="Group";
                                map.put(e.id,e);
                            }
                        }
                    } catch(Exception ignored) {}
                }
            }
        } catch(Exception ignored) {}
        Set<Long> scoped=new LinkedHashSet<>();collectUniverseIds(s.introspection,scoped);scoped.addAll(manual);
        for(long id:scoped)try{map.put(id,universe(s,id));}catch(Exception e){result.warnings.add("Universe "+id+": "+safe(e));}
        // Metadata requests are limited to explicitly scoped / manually added IDs, avoiding unnecessary key use.
        result.items.addAll(map.values());
        for(int offset=0;offset<result.items.size();offset+=50)try{
            StringBuilder ids=new StringBuilder();for(int j=offset;j<Math.min(offset+50,result.items.size());j++){if(ids.length()>0)ids.append(',');ids.append(result.items.get(j).id);}
            JSONArray thumbnails=json("https://thumbnails.roblox.com/v1/games/icons?universeIds="+ids+"&size=256x256&format=Png&isCircular=false",null,null).optJSONArray("data");
            if(thumbnails!=null)for(int i=0;i<thumbnails.length();i++){JSONObject t=thumbnails.getJSONObject(i);Experience e=map.get(t.optLong("targetId"));if(e!=null&&"Completed".equals(t.optString("state"))){e.icon=t.optString("imageUrl");e.iconState=t.optString("state","Unavailable");}}
        }catch(Exception ignored){}
        int retried=0;for(Experience e:result.items)if(e.icon.isEmpty()&&retried++<20)resolveIcon(e);return result;
    }
    void resolveIcon(Experience e){try{for(int attempt=0;attempt<2;attempt++){JSONArray data=json("https://thumbnails.roblox.com/v1/games/icons?universeIds="+e.id+"&returnPolicy=PlaceHolder&size=256x256&format=Png&isCircular=false",null,null).optJSONArray("data");if(data!=null)for(int n=0;n<data.length();n++){JSONObject t=data.getJSONObject(n);if(t.optLong("targetId")==e.id){e.iconState=t.optString("state","Unavailable");if("Completed".equals(e.iconState)&&!t.optString("imageUrl").isEmpty()){e.icon=t.optString("imageUrl");return;}}}if(!"Pending".equals(e.iconState))break;Thread.sleep(600);}if(e.rootPlace>0){JSONArray data=json("https://thumbnails.roblox.com/v1/places/gameicons?placeIds="+e.rootPlace+"&returnPolicy=PlaceHolder&size=256x256&format=Png&isCircular=false",null,null).optJSONArray("data");if(data!=null)for(int n=0;n<data.length();n++){JSONObject t=data.getJSONObject(n);if(t.optLong("targetId")==e.rootPlace&&"Completed".equals(t.optString("state"))&&!t.optString("imageUrl").isEmpty()){e.icon=t.optString("imageUrl");e.iconState="Completed";return;}}}}catch(Exception failure){e.iconState="Network error";}}
    static boolean imageHost(java.net.URL url){String h=url.getHost().toLowerCase(java.util.Locale.US);return "https".equals(url.getProtocol())&&(h.equals("rbxcdn.com")||h.endsWith(".rbxcdn.com")||h.equals("roblox.com")||h.endsWith(".roblox.com"));}
    android.graphics.Bitmap image(String location,int size)throws Exception{for(int n=0;n<5;n++){URL u=new URL(location);if(!imageHost(u))throw new IOException("Unexpected image host.");try(okhttp3.Response response=NetworkRuntime.client().newCall(new okhttp3.Request.Builder().url(location).get().build()).execute()){if(response.code()>=300&&response.code()<400){String next=response.header("Location");if(next==null)throw new IOException("Missing image redirect.");location=new URL(u,next).toString();continue;}if(!response.isSuccessful()||response.body()==null)throw new IOException("Image unavailable.");byte[] bytes;try(InputStream in=response.body().byteStream()){bytes=read(in,4*1024*1024);}android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(options.outWidth<1||options.outHeight<1||options.outWidth>8192||options.outHeight>8192)throw new IOException("Unexpected image dimensions.");options.inSampleSize=1;while(options.outWidth/options.inSampleSize>Math.max(size*2,128)||options.outHeight/options.inSampleSize>Math.max(size*2,128))options.inSampleSize*=2;options.inJustDecodeBounds=false;return android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);}}throw new IOException("Too many image redirects.");}
    public ArrayList<JSONObject> places(long universe) throws Exception {
        ArrayList<JSONObject> all=new ArrayList<>();String cursor="";Set<String> seen=new HashSet<>();
        do{JSONObject o=json("https://develop.roblox.com/v1/universes/"+universe+"/places?limit=100&sortOrder=Asc"+(cursor.isEmpty()?"":"&cursor="+enc(cursor)),null,null);
            JSONArray a=o.getJSONArray("data");for(int i=0;i<a.length();i++)all.add(a.getJSONObject(i));
            cursor=o.optString("nextPageCursor","");if("null".equals(cursor))cursor="";if(!cursor.isEmpty()&&!seen.add(cursor))throw new IOException("Repeated place pagination cursor.");
        }while(!cursor.isEmpty());return all;
    }
    private boolean trustedAssetUrl(URL u) {
        String h=u.getHost().toLowerCase(Locale.US);
        return "https".equals(u.getProtocol())&&(h.equals("rbxcdn.com")||h.endsWith(".rbxcdn.com")||h.equals("roblox.com")||h.endsWith(".roblox.com"));
    }
    public File download(Session s,long placeId,File directory) throws Exception {
        if(!s.canDownload())throw new IOException("Sign in with Roblox Web or add legacy-asset:manage to your API key.");
        if((s.key==null||s.key.isEmpty())&&s.hasCookie()){
            File tmp=downloadAssetWithAuth(placeId,null,s.cookie,directory);
            if(!validPlace(tmp)){tmp.delete();throw new IOException("Roblox returned a non-place file.");}
            return tmp;
        }
        String url=ROOT+"/asset-delivery-api/v1/assetId/"+placeId;
        HttpURLConnection initial=connection(url,s.key,"GET");File tmp=File.createTempFile("place-",".part",directory);
        try {
            int code=initial.getResponseCode();if(code<200||code>=300)throw error(code);
            String content=initial.getContentType();
            if(content!=null&&content.toLowerCase(Locale.US).contains("json")) {
                JSONObject o;try(InputStream in=initial.getInputStream()){o=new JSONObject(new String(read(in,1024*1024),"UTF-8"));}
                String location=o.optString("location","");
                if(location.isEmpty()){JSONArray locations=o.optJSONArray("locations");if(locations!=null&&locations.length()>0)location=locations.getJSONObject(0).optString("location");}
                if(location.isEmpty())throw new IOException("Roblox did not provide an asset download location.");
                fetchAsset(location,tmp);
            } else try(InputStream in=initial.getInputStream()){copy(in,tmp);}
            if(!validPlace(tmp))throw new IOException("Roblox returned a non-place file. Nothing was imported.");
            return tmp;
        } catch(Exception e){tmp.delete();throw e;}finally{initial.disconnect();}
    }
    private void fetchAsset(String location,File target) throws Exception {
        for(int redirects=0;redirects<5;redirects++){
            URL u=new URL(location);if(!trustedAssetUrl(u))throw new IOException("Blocked an unexpected asset host.");
            HttpURLConnection c=connection(location,null,"GET");c.setRequestProperty("Accept","application/octet-stream");
            try{int code=c.getResponseCode();if(code>=300&&code<400){String next=c.getHeaderField("Location");if(next==null)throw new IOException("Missing download redirect.");location=new URL(u,next).toString();continue;}
                if(code<200||code>=300)throw error(code);if(c.getContentLengthLong()>MAX_DOWNLOAD)throw new IOException("Place exceeds the 100 MiB first-layer limit.");
                try(InputStream in=c.getInputStream()){copy(in,target);}return;
            }finally{c.disconnect();}
        }throw new IOException("Too many asset redirects.");
    }
    private void copy(InputStream in,File target) throws IOException {
        long total=0;try(OutputStream out=new FileOutputStream(target)){byte[] buf=new byte[32768];int n;while((n=in.read(buf))!=-1){total+=n;if(total>MAX_DOWNLOAD)throw new IOException("Place exceeds the 100 MiB first-layer limit.");out.write(buf,0,n);}}
    }
    public static boolean validPlace(File file) throws IOException {
        byte[] b=new byte[512];int n;try(InputStream in=new FileInputStream(file)){n=in.read(b);}if(n<=0)return false;
        String head=new String(b,0,n,"UTF-8").trim();return head.startsWith("<roblox!")||head.startsWith("<roblox ")||head.startsWith("<roblox>")||head.startsWith("<?xml")&&head.contains("<roblox");
    }
    public static boolean xmlPlace(File file) throws IOException {
        byte[] b=new byte[32];int n;try(InputStream in=new FileInputStream(file)){n=in.read(b);}return n>0&&new String(b,0,n,"UTF-8").trim().startsWith("<?xml")||n>0&&new String(b,0,n,"UTF-8").trim().startsWith("<roblox ");
    }
    public static String safe(Throwable e) {
        if(e instanceof ApiException)return e.getMessage();
        if(e instanceof java.net.SocketTimeoutException)return "Connection timed out. Try again.";
        if(e instanceof java.net.UnknownHostException)return "Unable to reach Roblox. Check your connection.";
        if(e instanceof javax.net.ssl.SSLException)return "The secure connection to Roblox failed.";
        if(e instanceof JSONException)return "Roblox returned an unexpected response.";
        if(e instanceof IOException)return e.getMessage()==null?"File or network operation failed.":e.getMessage();
        // Unexpected failures keep their type and message so they can be diagnosed and reported.
        String message=e.getMessage();
        return e.getClass().getSimpleName()+(message==null||message.isEmpty()?"" : ": "+message);
    }
}
