package init;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javassist.*;


/** Initializer class for H2O.
 *
 * Unpacks all the dependencies and H2O implementation from the jar file, sets
 * the loader to be able to load all the classes properly and then executes the
 * main method of the H2O class.
 *
 * Does nothing if the H2O is not run from a jar archive. (This *is* a feature,
 * at least for the time being so that we can continue using different IDEs).
 */
public class Boot extends ClassLoader {

  public static final Boot _init;
  private final ZipFile _h2oJar;
  private final File _parentDir;
  File _binlib;
  // javassist support for rewriting class files
  private ClassPool _pool;      // The pool of altered classes
  private CtClass _h2oSerializable;  // The Compile-Time Class for "RemoteTask"
  private CtClass _remoteTask;  // The Compile-Time Class for "RemoteTask"

  static {
    try {
      _init = new Boot();
    } catch( Exception e ) {
      throw new Error(e);
    }
  }

  private Boot() throws IOException {
    final String ownJar = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
    ZipFile jar = null;
    File dir = null;
    if( ownJar.endsWith(".jar") ) { // do nothing if not run from jar
      jar = new ZipFile(URLDecoder.decode(ownJar, "UTF-8"));
      dir = File.createTempFile("h2o-temp-", "");
      if( !dir.delete() ) throw new IOException("Failed to remove tmp file: " + dir.getAbsolutePath());
      if( !dir.mkdir() )  throw new IOException("Failed to create tmp dir: "  + dir.getAbsolutePath());
      dir.deleteOnExit();
    }
    _h2oJar = jar;
    _parentDir = (dir==null) ? new File(".") : dir;
    // javassist support for rewriting class files
    _pool = ClassPool.getDefault();
  }

  public static void main(String[] args) throws Exception {  _init.boot(args); }

  private URLClassLoader _systemLoader;
  private Method _addUrl;

  private void boot( String[] args ) throws Exception {
    if( _h2oJar != null ) {
      _systemLoader = (URLClassLoader)getSystemClassLoader();
      _addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
      _addUrl.setAccessible(true);

      // Make all the embedded jars visible to the custom class loader
      extractInternalFiles(); // Extract e.g. SIGAR's .dll & .so files
      File binlib = internalFile("binlib");
      System.setProperty("org.hyperic.sigar.path", binlib.getAbsolutePath());
      addInternalJars("hexbase_impl.jar");
      addInternalJars("sigar");
      addInternalJars("apache");
      addInternalJars("gson");
      addInternalJars("junit");
      addInternalJars("jama");
      addInternalJars("poi");
      addInternalJars("trove");
    } else {
      System.setProperty("org.hyperic.sigar.path", "lib/binlib");
    }

    // Figure out the correct main class to call
    String mainClass = "water.H2O"; // Default mainClass
    if( args.length >= 2 && args[0].equals("-mainClass") ) {
      mainClass = args[1];    // Swap out for requested main
      args = Arrays.copyOfRange(args, 2, args.length);
    }

    // Call "main"!
    Class h2oclazz = loadClass(mainClass,true);
    h2oclazz.getMethod("main",String[].class).invoke(null,(Object)args);
  }

  /** Returns an external File for the internal file name. */
  public File internalFile(String name) { return new File(_parentDir, name); }

  /** Add a jar to the system classloader */
  public void addInternalJars(String name) throws IllegalAccessException, InvocationTargetException, MalformedURLException {
    addExternalJars(internalFile(name));
  }

  /** Adds all jars in given directory to the classpath. */
  public void addExternalJars(File file) throws IllegalAccessException, InvocationTargetException, MalformedURLException {
    assert file.exists() : "Unable to find external file: " + file.getAbsolutePath();
    if( file.isDirectory() ) {
      for( File f : file.listFiles() ) addExternalJars(f);
    } else if( file.getName().endsWith(".jar") ) {
      _addUrl.invoke(_systemLoader, file.toURI().toURL());
    }
  }

  /** Extracts the libraries from the jar file to given local path.   */
  private void extractInternalFiles() {
    Enumeration entries = _h2oJar.entries();
    while( entries.hasMoreElements() ) {
      ZipEntry e = (ZipEntry) entries.nextElement();
      String name = e.getName();
      File out = internalFile(name);
      out.getParentFile().mkdirs();
      if( e.isDirectory() ) continue; // mkdirs() will handle these

      // extract the entry
      try {
        BufferedInputStream  is = new BufferedInputStream (_h2oJar.getInputStream(e));
        BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out));
        int read;
        byte[] buffer = new byte[4096];
        while( (read = is.read(buffer)) != -1 ) os.write(buffer,0,read);
        os.flush();
        os.close();
        is.close();
      } catch( FileNotFoundException ex ) {
        // Expected FNF if 2 H2O instances are attempting to unpack in the same directory
      } catch( IOException ex ) {
        System.err.println("Unable to extract file "+name+" because "+ex);
      }
    }
  }

  public InputStream getResource2(String uri) {
    if( _h2oJar != null ) {
      return _systemLoader.getResourceAsStream("resources"+uri);
    } else { // to allow us to read things not only from the loader
      try {
        return new FileInputStream(new File("lib/resources"+uri));
      } catch (FileNotFoundException e) {
        return null;
      }
    }
  }

  // --------------------------------------------------------------------------
  //
  // Auto-Serialization!
  //
  // At Class-load-time, insert serializers for all subclasses of RemoteTask
  // that do not already contain serializers.  We are limited to serializing
  // primitives, arrays of primitivies, Keys, and Strings.
  //
  // --------------------------------------------------------------------------

  // Intercept class loads that would otherwise go to the parent loader
  // (probably the System loader) and try to auto-add e.g. serialization
  // methods to classes that inherit from RemoteTask.  Notice that this
  // changes the default search order: existing classes first, then my class
  // search, THEN the System or parent loader.
  public synchronized Class loadClass( String name, boolean resolve ) throws ClassNotFoundException {
    Class z = loadClass2(name,resolve);      // Do all the work in here
    if( resolve ) resolveClass(z);           // Resolve here instead in the work method
    return z;
  }

  // Run the class lookups in my favorite non-default order.
  private final Class loadClass2( String name, boolean resolve ) throws ClassNotFoundException {
    Class z = findLoadedClass(name); // Look for pre-existing class
    if( z != null ) return z;
    z = javassistLoadClass(name);    // Try the Happy Class Loader
    if( z != null ) return z;
    z = getParent().loadClass(name); // Try the parent loader.  Probably the System loader.
    if( z != null ) return z;
    return z;
  }

  // See if javaassist can find this class; if so then check to see if it is a
  // subclass of water.RemoteTask, and if so - alter the class before returning it.
  public synchronized Class javassistLoadClass( String name ) {
    try {
      CtClass cc = _pool.get(name); // Full Name Lookup
      if( cc == null ) return null; // Oops?  Try the system loader, but expected to work
      String pack = cc.getPackageName();
      if( !pack.startsWith("water") &&
          !pack.startsWith("hex") &&
          !pack.startsWith("test") &&
          !pack.startsWith("org.junit") &&
          true ) return null; // Not in my package
      // We need the RemoteTask CtClass before we can ask "subclassOf"
      if( _h2oSerializable == null )  // Lazily set the RemoteTask CtClass
        _h2oSerializable = _pool.get("init.H2OSerializable");
      if(_remoteTask == null){
        _remoteTask = _pool.get("water.RemoteTask");
        _remoteTask.toClass(this, null); // Go ahead and early load it
      }

      if( cc.isInterface() || _remoteTask == cc || // No need to rewrite the base class
          !cc.subtypeOf(_h2oSerializable)) // Not a child of RemoteTask
        return cc.toClass(this, null); // Just the same class with 'this' Boot class loader
      return javassistLoadClass(cc); // Add serialization methods
    } catch( NotFoundException nfe ) {
      return null;              // Not found?  Use the normal loader then
    } catch( CannotCompileException cce ) { // Expected to compile
      cce.printStackTrace();
      throw new RuntimeException(cce);
    }
  }

  public synchronized Class javassistLoadClass( CtClass cc ) throws NotFoundException, CannotCompileException {
    CtClass scc = cc.getSuperclass(); // See if the super is already done
    if( scc != null && scc.subtypeOf(_h2oSerializable) && !scc.isFrozen() && scc != _remoteTask)             // Super not done?
      javassistLoadClass(scc);        // Recursively serialize
    return addSerializationMethods(cc);
  }

  // Returns true if this method pre-exists *in the local class*.
  // Returns false otherwise, which requires a local method to be injected
  private static boolean hasExisting( String methname, String methsig, CtBehavior ccms[] ) throws NotFoundException {
    for( CtBehavior cm : ccms )
      if( cm.getName     ().equals(methname) &&
          cm.getSignature().equals(methsig ) )
        return true;
    return false;
  }

  // This method is handed a CtClass which is known to be a subclass of
  // water.RemoteTask.  Add any missing serialization methods.
  Class addSerializationMethods( CtClass cc ) throws CannotCompileException, NotFoundException {
    // Check for having "wire_len".  Either All or None of wire_len, read &
    // write must be defined.  Note that I use getDeclaredMethods which returns
    // only the local methods.  The singular getDeclaredMethod searches for a
    // specific method *up into superclasses*, which will trigger premature
    // loading of those superclasses.
    CtMethod ccms[] = cc.getDeclaredMethods();
    if( hasExisting("wire_len","()I",ccms) ) { // Already has serialization methods?
      assert hasExisting("write","(Ljava/io/DataOutputStream;)V",ccms);
      assert hasExisting("read" ,"(Ljava/io/DataInputStream;)V",ccms);
      assert hasExisting("write","(Lwater/Stream;)V",ccms);
      assert hasExisting("read" ,"(Lwater/Stream;)V",ccms);
      return cc.toClass(this, null);  // Has serialization methods already; blow off adding more
    }
    assert !hasExisting("write","(Ljava/io/DataOutputStream;)V",ccms);
    assert !hasExisting("read" ,"(Ljava/io/DataInputStream;)V",ccms);
    assert !hasExisting("write","(Lwater/Stream;)V",ccms);
    assert !hasExisting("read" ,"(Lwater/Stream;)V",ccms);

    // Add the serialization methods: wireLen, read, write.
    CtField ctfs[] = cc.getDeclaredFields();

    // We cannot call RemoteTask.xxx, as these methods always throw a
    // RuntimeException (to make sure we noisely fail instead of silently
    // fail).  But we DO need to call the super-chain of serialization methods
    // - except for RemoteTask.
    boolean callsuper = (cc.getSuperclass() != _remoteTask && cc.getSuperclass().subtypeOf(_h2oSerializable));

    // Build a null-ary constructor if needed
    String clzname = cc.getSimpleName();
    if( !hasExisting(clzname,"()V",cc.getConstructors()) ) {
      String body = "public "+clzname+"() { }";
      cc.addConstructor(CtNewConstructor.make(body,cc));
    }
    // Running example is:
    //   class Crunk extends RemoteTask {
    //     int _x;  int _xs[];  double _d;
    //   }

    // Build a wireLen method that looks something like this:
    //     public int wireLen() { return 4+4+(_xs==null?0:(_xs.length*4))8+0; }
    make_body(cc,ctfs,callsuper,
              "public int wire_len() {\n  int res = 0;\n",
              " res += super.wire_len();\n",
              "  res += %d;\n",
              "  res += water.UDP.wire_len(%s);\n",
              "  res += (%s==null?1:%s.wire_len());\n",
              "  res += 2+(%s==null?0:%s.length());\n",
              "  res += 1 + (%s==null?0:%s.wire_len() + 2+%s.getClass().getName().length());\n",
              // ------------------ object array ----------------------------------------------------------
              "  res += 4;\n"+
              "  if(%s != null) {\n"+
              "    res += 2 + %s.getClass().getComponentType().getName().length();\n"+
              "    for(int i = 0; i < %s.length; ++i)\n" +
              "      res += 1 + ((%s[i] != null)?%s[i].wire_len():0);\n" +
              "  }\n",
              // ------------------ end of object array -----------------------------------------------------
              "\n  return res;\n}");

    // Build a write method that looks something like this:
    //     public void write( DataOutputStream dos ) throws IOException {
    //       dos.writeInt(_x);
    //       dos.writeAry(_xs);
    //       dos.writeDouble(_d);
    //     }
    make_body(cc,ctfs,callsuper,
              "public void write(java.io.DataOutputStream dos) throws java.io.IOException {\n",
              "  super.write(dos);\n",
              "  dos.write%S(%s);\n",
              "  water.TCPReceiverThread.writeAry(dos,%s);\n",
              "  if( %s == null ) dos.writeByte(-1); else %s.write(dos);\n",
              "  water.TCPReceiverThread.writeStr(dos,%s);\n",
              "  dos.writeBoolean(%s != null); \n  if(%s != null){\n    water.TCPReceiverThread.writeStr(dos,%s.getClass().getName());\n    %s.write(dos);\n  }\n",
              // ------------------ object array ----------------------------------------------------------
              "  if(%s == null) {\n"+
              "    dos.writeInt(-1);\n"+
              "  }else {\n"+
              "    dos.writeInt(%s.length);\n"+
              "    water.TCPReceiverThread.writeStr(dos,%s.getClass().getComponentType().getName());\n" +
              "    for(int i  = 0; i < %s.length; ++i){\n" +
              "      dos.writeBoolean(%s[i] != null);\n" +
              "      if(%s[i] != null){\n"+
              "         %s[i].write(dos);\n"+
              "      }\n" +
              "   }\n" +
              " }\n",
              // ------------------ end of object array -----------------------------------------------------
              "}");

    // Build a read method that looks something like this:
    //     public void read( DataInputStream dos ) throws IOException {
    //       _x = dis.readInt();
    //       _xs = dis.readIntAry();
    //       _d = dis.readDouble();
    //     }
    make_body(cc,ctfs,callsuper,
              "public void read(java.io.DataInputStream dis) throws java.io.IOException {\n",
              "  super.read(dis);\n",
              "  %s = dis.read%S();\n",
              "  %s = water.TCPReceiverThread.read%SAry(dis);\n",
              "  %s = water.Key.read(dis);\n",
              "  %s = water.TCPReceiverThread.readStr(dis);\n",
              "  if(dis.readBoolean()){\n    String cn = water.TCPReceiverThread.readStr(dis);   %s = (%C)Class.forName(cn).newInstance();\n    %s.read(dis);\n  }\n",
              // ------------------ object array ----------------------------------------------------------
              "  int n = dis.readInt();\n" +
              "  if (n != -1) {" +
              "    %s = new %C[n];\n" +
              "    String cn = water.TCPReceiverThread.readStr(dis);\n" +
              "    Class c = Class.forName(cn);\n" +
              "    for(int i  = 0; i < n; ++i){\n" +
              "      if(dis.readBoolean()) {\n" +
              "        %s[i] = (%C)c.newInstance();\n" +
              "        %s[i].read(dis);\n" +
              "      }\n" +
              "    }\n" +
              "  }\n",
              // ------------------ end of object array -----------------------------------------------------
              "}\n");

    // Build a write method that looks something like this:
    //     public void write( Stream s ) {
    //       s.set4(_x);
    //       s.set(_xs);
    //       s.set8d(_d);
    //     }
    make_body(cc,ctfs,callsuper,
              "public void write(water.Stream s) {\n",
              "  super.write(s);\n",
              "  s.set%z(%s);\n",
              "  s.setAry%z(%s);\n",
              "  if( %s == null ) s.set1(-1); else %s.write(s);\n",
              "  s.setLen2Str(%s);",
              "  s.setz(%s!=null);\n  if( %s != null ){\n    s.setLen2Str(%s.getClass().getName()); %s.write(s);\n  }\n",
              // ------------------ object array ----------------------------------------------------------
              "  if(%s == null)s.set4(-1); else {\n"+
              "    s.set4(%s.length);\n" +
              "    s.setLen2Str(%s.getClass().getComponentType().getName());\n" +
              "    for(int i  = 0; i < %s.length; ++i){\n" +
              "      s.setz(%s[i] != null);\n" +
              "      if(%s[i] != null){\n"+
              "         %s[i].write(s);\n"+
              "      }\n" +
              "   }\n" +
              " }\n",
              // ------------------ end of object array -----------------------------------------------------
              "}");
    // Build a read method that looks something like this:
    //     public void read( Stream s ) {
    //       _x = s.get4();
    //       _xs = s.getAry4();
    //       _d = s.get8d();
    //     }
    make_body(cc,ctfs,callsuper,
              "public void read(water.Stream s) {\n",
              "  super.read(s);\n",
              "  %s = s.get%z();\n",
              "  %s = s.getAry%z();\n",
              "  %s = water.Key.read(s);\n",
              "  %s = s.getLen2Str();\n",
              "  if(s.getz()){\n   String cn = s.getLen2Str();\n    %s = (%C)Class.forName(cn).newInstance();\n    %s.read(s);\n  }\n",
              // ------------------ object array ----------------------------------------------------------
              "  int n = s.get4();\n" +
              "  if(n != -1) { \n" +
              "    %s = new %C[n];\n" +
              "    Class c = Class.forName(s.getLen2Str());\n" +
              "    for(int i  = 0; i < n; ++i){\n" +
              "      if(s.getz()) {\n" +
              "        %s[i] = (%C)c.newInstance();\n" +
              "        %s[i].read(s);\n" +
              "      }\n" +
              "    }\n" +
              "  }\n",
              // ------------------ end of object array -----------------------------------------------------
              "}");
    // Make the class public
    cc.setModifiers(javassist.Modifier.setPublic(cc.getModifiers()));

    return cc.toClass(this, null);
  }

  static HashSet<String> _currentClasses = new HashSet<String>();

  private static synchronized boolean canProcess(CtClass cc){
    if(cc.isFrozen())return false;
    return _currentClasses.contains(cc.getName());

  }
  // Produce a code body with all these fill-ins.
  private final void make_body(CtClass cc, CtField[] ctfs, boolean callsuper,
                               String header,
                               String supers,
                               String prims,
                               String primarys,
                               String keys,
                               String strs,
                               String object,
                               String objectArr,
                               String trailer
                               ) throws CannotCompileException, NotFoundException{
    StringBuilder sb = new StringBuilder();
    sb.append(header);
    if( callsuper ) sb.append(supers);
    for( CtField ctf : ctfs ) {
      int mods = ctf.getModifiers();
      if( javassist.Modifier.isTransient(mods) || javassist.Modifier.isStatic(mods) )
        continue;  // Only serialize not-transient instance fields (not static)
      int ftype = ftype(ctf);   // Field type encoding
      if     ( ftype <= 7 ) sb.append(prims);
      else if( ftype == 8 ) sb.append(keys);
      else if( ftype == 9 ) sb.append(strs);
      else if( ftype == OBJ_ARR_TYPE ||  ftype == OBJ_TYPE) {
        CtClass c = ((ftype == OBJ_ARR_TYPE)?ctf.getType().getComponentType():ctf.getType());
        assert c.subtypeOf(_h2oSerializable);
        // todo detect cycles!
        if(!c.isModified() && !c.isFrozen()){
          // check if we're the inner class!
          if(!cc.getName().startsWith(c.getName()))
            javassistLoadClass(c);
          else
            continue;
        }
        sb.append(ftype == OBJ_ARR_TYPE?objectArr:object);
        // gadd serialization to the object if needed
        try {
          c.getDeclaredMethod("wire_len");
        } catch( NotFoundException e ) {
          assert false:"missing generated methods in class " + c.getName();
        }
        subsub(sb,"%C",c.getName().replace('$','.'));
      } else if( ftype >=10 ) sb.append(primarys);
      subsub(sb,"%s",ctf.getName()); // %s ==> field name
      subsub(sb,"%d",FLDSZ0[ftype]); // %d ==> field size in bytes, so 1 (bools,bytes) up to 8 (longs/doubles)
      subsub(sb,"%z",FLDSZ1[ftype]); // %z ==> as %d, but with trailing f/d so 8 (longs) and 8d (doubles)
      subsub(sb,"%S",FLDSZ2[ftype]); // %S ==> Byte, Short, Char, Int, Float, Double, Long
    }
    sb.append(trailer);
    String body = sb.toString();
    try {
      //System.out.println(body);  // Uncomment me to see the generated code
      cc.addMethod(CtNewMethod.make(body,cc));
    } catch( CannotCompileException ce ) {
      System.out.println("--- Compilation failure while compiler serializers for "+cc.getName());
      System.out.println(body);
      System.out.println("------");
      throw ce;
    }
  }

  // Field size in bytes, from type.  For arrays, it's the element size.
  static private final String[] FLDSZ0 = {
    "1","1","2","2","4","4" ,"8","8" ,"-1","-1", // prims, Key, String
    "1","1","2","2","4","4" ,"8","8" ,"-1","-1", // prim[]
    "1","1","2","2","4","4" ,"8","8",             // prim[][]
    "-1"
  };
  static private final String[] FLDSZ1 = {
    "z","1","2","2","4","4f","8","8d","-1","-1", // prims, Key, String
    "z","1","2","2","4","4f","8","8d","-1","-1", // prim[]
    "zz","11","22","22","44","4fef","88","8d8d", // prim[][]
    "-1"
  };
  static private final String[] FLDSZ2 = {
    "Boolean","Byte","Char","Short","Int","Float","Long","Double","Key","String",
    "Boolean","Byte","Char","Short","Int","Float","Long","Double","Key","String",
    "BooleanBoolean","ByteByte","CharChar","ShortShort","IntInt","FloatFloat","LongLong","DoubleDouble",
    "-1"
  };

  public static final int OBJ_TYPE = 18;
  public static final int OBJ_ARR_TYPE = 19;
  // Field types:
  // 0-7: primitives
  // 8: Key
  // 9: String
  // 10-17: array-of-prim\
  // 18: H2OSerializable
  // Barfs on all others (eg Values or array-of-Frob, etc)
  private int ftype( CtField ctf ) { return ftype0(ctf,0); }
  private int ftype0( CtField ctf, int idx ) {
    String sig = ctf.getSignature();
    switch( sig.charAt(idx) ) {
    case 'Z': return 0;         // Booleans: I could compress these more
    case 'B': return 1;         // Primitives
    case 'C': return 2;
    case 'S': return 3;
    case 'I': return 4;
    case 'F': return 5;
    case 'J': return 6;
    case 'D': return 7;
    case 'L':                   // Handled classes
      if( sig.equals("Lwater/Key;") ) return 8;
      if( sig.equals("Ljava/lang/String;") ) return 9;
      try {
        CtClass ct = ctf.getType();
        if(idx > 0 && ct.isArray()){
          CtClass cct = ct.getComponentType();
          if(cct.subtypeOf(_h2oSerializable)) {
            if(!cct.getName().contains("$") || javassist.Modifier.isStatic(cct.getModifiers())) // non static inner classes, not allowed
              return OBJ_ARR_TYPE-10;
            else throw barf(ctf,"can not serialize non-static inner classes");
          }
        } else if(ct.subtypeOf(_h2oSerializable)){
          if(!ct.getName().contains("$") || javassist.Modifier.isStatic(ct.getModifiers())) // non static inner classes, not allowed
            return OBJ_TYPE;
          else
            throw barf(ctf,"can not serialize non-static inner classes");
        }
      } catch( NotFoundException e ) {}
      break;
    case '[':                   // Arrays
      return ftype0(ctf,idx+1)+10; // Same as prims, plus 10
    }
    throw barf(ctf);
  }

  // Replace 2-byte strings like "%s" with s2.
  static private void subsub( StringBuilder sb, String s1, String s2 ) {
    int idx;
    while( (idx=sb.indexOf(s1)) != -1 ) sb.replace(idx,idx+2,s2);
  }


  private static Error barf( CtField ctf ) {
    return new Error("Serialization of field "+ctf.getName()+" not implemented");
  }

  private static Error barf( CtField ctf, String msg) {
    return new Error("Serialization of field "+ctf.getName()+" not implemented, " + msg);
  }
}
