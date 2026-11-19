package local.simcity.offline;
public final class BootstrapApplication extends com.gamecomb.gcframework.GCApplication {
 public void attachBaseContext(android.content.Context base){
  java.io.File root=base.getExternalFilesDir(null);
  if(root==null)throw new IllegalStateException("Local storage unavailable");
  if(!new java.io.File(root,"0.pb").isFile()){
   try{java.util.zip.ZipInputStream in=new java.util.zip.ZipInputStream(base.getAssets().open("offline-bootstrap.zip"));java.util.zip.ZipEntry e;String prefix=root.getCanonicalPath()+java.io.File.separator;
    byte[] buffer=new byte[65536];
    while((e=in.getNextEntry())!=null){java.io.File f=new java.io.File(root,e.getName());if(!f.getCanonicalPath().startsWith(prefix))throw new java.io.IOException("Invalid bootstrap path");
     if(e.isDirectory()){f.mkdirs();continue;}if(f.exists())continue;java.io.File parent=f.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new java.io.IOException("Cannot create local directory");
     java.io.File temp=new java.io.File(f.getPath()+".tmp");java.io.FileOutputStream out=new java.io.FileOutputStream(temp);int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);out.getFD().sync();out.close();if(!temp.renameTo(f))throw new java.io.IOException("Cannot commit bootstrap file");
    }in.close();android.util.Log.i("SimCityBootstrap","Local starter state created");
   }catch(java.io.IOException e){throw new IllegalStateException("Local starter initialization failed",e);}
  }
  super.attachBaseContext(base);
  local.simcity.offline.UiCleaner.start(this);
 }
}
