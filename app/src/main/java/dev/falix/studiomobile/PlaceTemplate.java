package dev.falix.studiomobile;
import java.util.*;
/**
 * Produces the Studio "Baseplate" template as a real Roblox XML data model.
 *
 * Geometry and colours match the values Studio writes:
 *  - Baseplate: Part, Size (512, 20, 512), CFrame (0, -10, 0) so its top face is exactly Y = 0,
 *    BrickColor "Medium stone grey" (163, 162, 165), Material Plastic (256) and Smooth surfaces.
 *  - SpawnLocation: Size (6, 1, 6), CFrame (0, 0.5, 0) so it rests on the baseplate top face,
 *    Anchored/CanCollide/Neutral/Enabled, Smooth surfaces.
 *  - Lighting carries the documented defaults, plus the Atmosphere/Clouds/Sky objects that make a
 *    Studio place render the way the desktop editor shows it.
 */
final class PlaceTemplate {
 static final long BASE_GREY = 0xFFA3A2A5L;      // BrickColor Medium stone grey
 static final int SURFACE_SMOOTH = 0, SURFACE_INLET = 4;

 static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
 static String item(String cls,String name,String properties,String children){return "<Item class=\""+cls+"\" referent=\"RBX"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.US)+"\"><Properties><string name=\"Name\">"+esc(name)+"</string>"+properties+"</Properties>"+children+"</Item>";}
 static String vec(String name,double x,double y,double z){return "<Vector3 name=\""+name+"\"><X>"+x+"</X><Y>"+y+"</Y><Z>"+z+"</Z></Vector3>";}
 static String frame(double x,double y,double z){return "<CoordinateFrame name=\"CFrame\"><X>"+x+"</X><Y>"+y+"</Y><Z>"+z+"</Z><R00>1</R00><R01>0</R01><R02>0</R02><R10>0</R10><R11>1</R11><R12>0</R12><R20>0</R20><R21>0</R21><R22>1</R22></CoordinateFrame>";}

 /** Six Smooth surfaces, the way Studio writes a freshly inserted part. */
 static String surfaces(){return "<token name=\"TopSurface\">"+SURFACE_SMOOTH+"</token><token name=\"BottomSurface\">"+SURFACE_SMOOTH+"</token>"
   +"<token name=\"LeftSurface\">"+SURFACE_SMOOTH+"</token><token name=\"RightSurface\">"+SURFACE_SMOOTH+"</token>"
   +"<token name=\"FrontSurface\">"+SURFACE_SMOOTH+"</token><token name=\"BackSurface\">"+SURFACE_SMOOTH+"</token>";}

 static String part(String cls,String name,double y,double x,double sy,double z,long color,String extras){
   return item(cls,name,"<bool name=\"Anchored\">true</bool><bool name=\"CanCollide\">true</bool><float name=\"Transparency\">0</float>"
     +"<Color3uint8 name=\"Color3uint8\">"+color+"</Color3uint8><token name=\"Material\">256</token>"
     +vec("size",x,sy,z)+frame(0,y,0)+extras+surfaces(),"");}

 static String generate(String name,String template,double gravity,boolean streaming){
   String children="";
   if("baseplate".equals(template)){
     children+=part("Part","Baseplate",-10,512,20,512,BASE_GREY,"");
     children+=part("SpawnLocation","SpawnLocation",0.5,6,1,6,BASE_GREY,
       "<bool name=\"Enabled\">true</bool><bool name=\"Neutral\">true</bool><float name=\"Duration\">0</float>"
       +"<bool name=\"AllowTeamChangeOnTouch\">false</bool><token name=\"TeamColor\">194</token>");
   }
   String lighting="<float name=\"ClockTime\">14</float><float name=\"Brightness\">2</float>"
     +"<Color3 name=\"Ambient\"><R>0</R><G>0</G><B>0</B></Color3>"
     +"<Color3 name=\"OutdoorAmbient\"><R>0.501961</R><G>0.501961</G><B>0.501961</B></Color3>"
     +"<bool name=\"GlobalShadows\">true</bool><float name=\"EnvironmentDiffuseScale\">0</float>"
     +"<float name=\"EnvironmentSpecularScale\">1</float><float name=\"ExposureCompensation\">0</float>"
     +"<float name=\"FogStart\">0</float><float name=\"FogEnd\">100000</float>"
     +"<Color3 name=\"FogColor\"><R>0.752941</R><G>0.752941</G><B>0.752941</B></Color3>"
     +"<Color3 name=\"ColorShift_Top\"><R>0</R><G>0</G><B>0</B></Color3>"
     +"<Color3 name=\"ColorShift_Bottom\"><R>0</R><G>0</G><B>0</B></Color3>"
     +"<float name=\"GeographicLatitude\">41.7333</float><string name=\"TimeOfDay\">14:00:00</string><token name=\"Technology\">1</token>";
   String sky=item("Sky","Sky","<bool name=\"CelestialBodiesShown\">true</bool><float name=\"StarCount\">3000</float><float name=\"SunAngularSize\">21</float><float name=\"MoonAngularSize\">11</float>","");
   String atmosphere=item("Atmosphere","Atmosphere","<float name=\"Density\">0.3</float><float name=\"Offset\">0</float><float name=\"Glare\">0</float><float name=\"Haze\">0</float><float name=\"Decay\">0.4</float><Color3 name=\"Color\"><R>0.780392</R><G>0.780392</G><B>0.780392</B></Color3>","");
   String clouds=item("Clouds","Clouds","<float name=\"Cover\">0.7</float><float name=\"Density\">0.7</float><Color3 name=\"Color\"><R>1</R><G>1</G><B>1</B></Color3>","");
   return "<roblox xmlns:xmime=\"http://www.w3.org/2005/05/xmlmime\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" version=\"4\">"
     +"<External>null</External><External>nil</External>"
     +item("Workspace","Workspace","<float name=\"Gravity\">"+gravity+"</float><bool name=\"StreamingEnabled\">"+streaming+"</bool><bool name=\"FilteringEnabled\">true</bool>",children)
     +item("Lighting","Lighting",lighting,sky+atmosphere)
     +item("Terrain","Terrain","",clouds)
     +item("ReplicatedStorage","ReplicatedStorage","","")
     +item("ServerScriptService","ServerScriptService","","")
     +item("ServerStorage","ServerStorage","","")
     +item("StarterGui","StarterGui","","")
     +item("StarterPack","StarterPack","","")
     +item("StarterPlayer","StarterPlayer","","")
     +"</roblox>\n";
 }
}
