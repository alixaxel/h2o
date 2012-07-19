/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package water.web;

import java.util.Properties;

import water.H2O;
import water.Key;
import water.Value;

/**
 *
 * @author peta
 */
public class DebugView extends H2OPage {
  
  public static final int KEYS_PER_PAGE = 25;

  public DebugView() {
    _refresh = 5;
  }
  
  @Override protected String serve_impl(Properties args) {
    RString response = new RString(html);
    // get the offset index
    int offset = 0;
    try {
      offset = Integer.valueOf(args.getProperty("o", "0"));
    } catch( NumberFormatException e ) { /* pass */ }
    // write the response
    H2O cloud = H2O.CLOUD;         // Current eldest Cloud
    Object[] keys = H2O.keySet().toArray();
    int lastIndex = keys.length;
    // get only the prefixed ones
    String prefix = args.getProperty("Prefix","");
    if (!prefix.isEmpty()) {
      int i = 0;
      for (int j = 0; j< keys.length; ++j) {
        if (((Key)keys[j]).toString().startsWith(prefix)) {
          if (i!=j) {
            Object s = keys[i];
            keys[i] = keys[j];
            keys[j] = s;
          }
          ++i;
        }          
      }
      lastIndex = i;
    }
    formatPagination(offset,lastIndex,response);
    offset *= KEYS_PER_PAGE;
    int i = 0;
    for( Object o : keys ) {
      if (i>=lastIndex) break;
      Key key = (Key)o;
      // skip keys at the beginning 
      if (offset>0) {
        --offset;
        continue;
      }
      Value val = H2O.raw_get(key);
      if( val == null) {  // Internal sentinel
        continue; 
      }
      formatKeyRow(cloud,key,val,response);
      if( ++i >= KEYS_PER_PAGE ) break;     // Stop at some reasonable limit
    }
    response.replace("noOfKeys",lastIndex);
    response.replace("cloud_name",H2O.CLOUD.NAME);
    response.replace("node_name",H2O.SELF.toString());
    if (!prefix.isEmpty()) 
      response.replace("pvalue","value='"+prefix+"'");
    return response.toString();
  }
  private void formatPagination(int offset, int size, RString response) {
    if (size<=KEYS_PER_PAGE)
      return;
    StringBuilder sb = new StringBuilder();
    sb.append("<div class='pagination pagination-centered' style='margin:0px auto'><ul>");
    if (offset!=0) {
      sb.append("<li><a href='?o=0'>First</li>");
      sb.append("<li><a href='?o="+(offset-1)+"'>&lt;&lt;</a></li>");
    }
    int j = 0;
    int i = offset - 5;
    while (j<10) {
      if (++i<0)
        continue;
      if (i>size/KEYS_PER_PAGE)
        break;
      if (i==offset)
        sb.append("<li class='active'><a href=''>"+i+"</li>");
      else 
        sb.append("<li><a href='?o="+i+"'>"+i+"</li>");
      ++j;
    }
    if (offset < (size/KEYS_PER_PAGE)) {
      sb.append("<li><a href='?o="+(offset+1)+"'>&gt;&gt;</a></li>");
      sb.append("<li><a href='?o="+(size/KEYS_PER_PAGE)+"'>Last</a></li>");
    }
    sb.append("</ul></div>");
    String nav = sb.toString();
    response.replace("navup",nav);
  }
  
  private void formatKeyRow(H2O cloud, Key key, Value val, RString response) {
    RString row = response.restartGroup("tableRow");
    // Dump out the Key
    String ks = key.toString();
    row.replace("keyHref",urlEncode(new String(key._kb)));
    row.replace("key",key.user_allowed() ? ks : "<code>"+key.toString()+"</code>");
    // Dump out the current replication info: Mem/Disk/Replication_desired
    int d = key.count_disk_replicas();
    int r = key.desired();
    if( d < r )
      row.replace("replicationStyle","background-color:#ffc0c0;color:#ff0000;");
    row.replace("r1",d);
    row.replace("r2",r);
    row.replace("home",cloud._memary[key.home(cloud)]);
    // Dump out the 2nd replica
    int idx2 = cloud.D(key,1);
    if( idx2 != -1 )
      row.replace("home2",cloud._memary[idx2]);
    int repl = key.replica(cloud);
    row.replace("replica",(repl==255?"":("r"+repl)));
    row.replace("clock",val.vector_clock_string());
    row.replace("class",val.getClass().getName());
    row.replace("max",val._max);
    row.replace("in_mem",val.mem()==null ? "null" : val.mem().length);
    // Now the first 100 bytes of Value as a String
    if (val.is_deleted())
      row.replace("delBtnStyle","visibility:hidden");
    row.replace("ktr",urlEncode(ks));
    row.replace("persist",val.persistenceBackend().name());
    row.replace("pstate",Value.persistenceStateToString(val.persistenceState()));
    row.append();
  }

  final static String html =
            "<div class='alert alert-success'>"
          + "You are connected to cloud <strong>%cloud_name</strong> and node <strong>%node_name</strong>."
          + "</div>"
          + "<form class='well form-inline' action='DebugView'>"
          + "  <input type='text' class='input-small span10' placeholder='filter prefix' name='Prefix' id='Prefix' %pvalue maxlength='512'>"
          + "  <button type='submit' class='btn btn-primary'>Filter keys!</button>"
          + "</form>"
          + "<p>The Local Store has %noOfKeys keys"
          + "<p>%navup</p>"
          + "<table class='table table-striped table-bordered table-condensed'>"
          + "<colgroup><col/><col/><col style=\"text-align:center\"/><col/></colgroup>\n"
          + "<thead><th>Key<th>D/R<th>1st<th>2nd<th>replica#<th>Clock<th>Class<th>Max<th>In Mem<th>Persistence<th>State</thead>\n"
          + "<tbody>"
          + "%tableRow{"
          + "  <tr>"
          + "    <td><a style='%delBtnStyle' href='RemoveAck?Key=%ktr'><button class='btn btn-danger btn-mini'>X</button></a>&nbsp;&nbsp;<a href='/Get?Key=%keyHref'>%key</a></td>"
          + "    <td style='%replicationStyle'>%r1/%r2</td>"
          + "    <td>%home</td>"
          + "    <td>%home2</td>"
          + "    <td>%replica</td>"
          + "    <td>%clock</td>"
          + "    <td>%class</td>"
          + "    <td>%max</td>"
          + "    <td>%in_mem</td>"
          + "    <td>%persist</td>"
          + "    <td>%pstate</td>"
          + "  </tr>\n"
          + "}"
          + "</tbody>"
          + "</table>\n"
          ;

//  final static RString response = new RString(html);
  
}
