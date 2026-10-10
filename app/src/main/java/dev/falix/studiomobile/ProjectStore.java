package dev.falix.studiomobile;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.database.Cursor;
import java.io.*;
import java.util.*;

final class ProjectStore {
    static final class Project { String name,location;Uri uri;long size,modified;Project(String n,Uri u,long s,long m,String l){name=n;uri=u;size=s;modified=m;location=l;} }
    final Context context;final SharedPreferences prefs;
    ProjectStore(Context c){context=c;prefs=c.getSharedPreferences("settings",Context.MODE_PRIVATE);}
    boolean fullAccess(){return Build.VERSION.SDK_INT>=30?Environment.isExternalStorageManager():context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED;}
    Uri tree(){String s=prefs.getString("projectTree",null);return s==null?null:Uri.parse(s);}
    boolean available(){return fullAccess()||tree()!=null;}
    void setTree(Uri uri){prefs.edit().putString("projectTree",uri.toString()).apply();}
    String location(){return tree()!=null?"Selected project folder":"Documents / StudioMobile";}
    ArrayList<Project> scan() throws Exception {
        ArrayList<Project> out=new ArrayList<>();Uri tree=tree();
        if(tree!=null){scanTree(tree,DocumentsContract.getTreeDocumentId(tree),out,0);}
        else if(fullAccess()){File docs=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);scanFiles(docs,out,0,new HashSet<>());}
        out.sort((a,b)->Long.compare(b.modified,a.modified));return out;
    }
    private boolean place(String name){String n=name.toLowerCase(Locale.US);return n.endsWith(".rbxl")||n.endsWith(".rbxlx");}
    private void scanFiles(File folder,ArrayList<Project> out,int depth,Set<String> seen) throws Exception {
        if(depth>8||out.size()>=2000||!seen.add(folder.getCanonicalPath()))return;
        File[] files=folder.listFiles();if(files==null)return;
        for(File f:files){if(out.size()>=2000)return;if(f.isDirectory())scanFiles(f,out,depth+1,seen);else if(place(f.getName()))out.add(new Project(f.getName(),Uri.fromFile(f),f.length(),f.lastModified(),f.getParent()));}
    }
    private void scanTree(Uri tree,String id,ArrayList<Project> out,int depth) throws Exception {
        if(depth>8||out.size()>=2000)return;
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id);
        try(Cursor c=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED},null,null,null)) {
            if(c==null)return;while(c.moveToNext()){if(out.size()>=2000)return;
                String child=c.getString(0),name=c.getString(1),mime=c.getString(2);
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))scanTree(tree,child,out,depth+1);
                else if(name!=null&&place(name))out.add(new Project(name,DocumentsContract.buildDocumentUriUsingTree(tree,child),c.getLong(3),c.getLong(4),"Selected project folder"));
            }
        }
    }
    private String sanitize(String name){String s=name.replaceAll("[^\\p{L}\\p{N} ._-]","_").trim();return s.isEmpty()?"Place":s.substring(0,Math.min(s.length(),80));}
    Uri importFile(File source,String base,boolean xml) throws Exception {
        if(!available())throw new IOException("Choose a project folder or allow all-files access before importing.");
        String name=sanitize(base)+"-"+System.currentTimeMillis()+(xml?".rbxlx":".rbxl");Uri tree=tree();
        if(tree!=null){
            Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
            Uri target=DocumentsContract.createDocument(context.getContentResolver(),parent,"application/octet-stream",name);
            if(target==null)throw new IOException("The folder provider could not create a file.");
            try(InputStream in=new FileInputStream(source);OutputStream out=context.getContentResolver().openOutputStream(target,"w")){if(out==null)throw new IOException("Unable to open output file.");transfer(in,out);}
            catch(Exception e){try{DocumentsContract.deleteDocument(context.getContentResolver(),target);}catch(Exception ignored){}throw e;}return target;
        }
        File directory=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),"StudioMobile");
        if(!directory.exists()&&!directory.mkdirs())throw new IOException("Unable to create Documents/StudioMobile.");
        File target=new File(directory,name),part=new File(directory,name+".part");
        try{try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(part)){transfer(in,out);}if(!part.renameTo(target))throw new IOException("Unable to finish the project file.");return Uri.fromFile(target);}
        catch(Exception e){part.delete();throw e;}
    }
    File stageLocal(Uri uri) throws Exception {
        File tmp=File.createTempFile("import-",".part",context.getCacheDir());
        try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(tmp)){
            if(in==null)throw new IOException("Cannot read this file.");transfer(in,out);
        }catch(Exception e){tmp.delete();throw e;}
        if(!RobloxApi.validPlace(tmp)){tmp.delete();throw new IOException("Select a valid .rbxl or .rbxlx place file.");}return tmp;
    }
    private void transfer(InputStream in,OutputStream out) throws IOException {byte[] b=new byte[32768];int n;long total=0;while((n=in.read(b))!=-1){total+=n;if(total>RobloxApi.MAX_DOWNLOAD)throw new IOException("File exceeds the 100 MiB first-layer limit.");out.write(b,0,n);}}
    String sourceName(Uri uri){
        try(Cursor c=context.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0).replaceAll("(?i)\\.rbxlx?$","");}catch(Exception ignored){}return "Imported place";
    }
    void touch(Project p){prefs.edit().putLong("recent:"+p.uri.toString(),System.currentTimeMillis()).apply();}
    ArrayList<Project> recent(ArrayList<Project> projects){ArrayList<Project> a=new ArrayList<>();for(Project p:projects)if(prefs.contains("recent:"+p.uri))a.add(p);a.sort((x,y)->Long.compare(prefs.getLong("recent:"+y.uri,0),prefs.getLong("recent:"+x.uri,0)));return a;}
}
