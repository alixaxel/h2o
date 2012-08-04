package water;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;

/**
 * A remote execution request
 *
 * @author <a href="mailto:cliffc@0xdata.com"></a>
 * @version 1.0
 */

public class TaskRemExec extends DFutureTask<RemoteTask> {

  final RemoteTask _dt;              // Task to send & execute remotely
  final Key _args;
  final boolean _did_put;

  // With a Key+Value, do a Put on the Key & block for it - forcing the Value
  // to be available when remote execution starts.
  public TaskRemExec( H2ONode target, RemoteTask dt, Key args, Value val ) {
    super( target,UDP.udp.rexec );
    _dt = dt;
    _args = args;
    _did_put = true;
    DKV.put(args,val);          // Publish the keyset for remote execution
    DKV.write_barrier();        // Block until all prior writes have completed
    resend();                   // Initial send after final fields set
  }

  // This version assumes a prior remote Value is already coherent
  public TaskRemExec( H2ONode target, RemoteTask dt, Key args ) {
    super( target,UDP.udp.rexec );
    _dt = dt;
    _args = args;
    _did_put = false;
    resend();                   // Initial send after final fields set
  }

  // Pack classloader/class & the instance data into the outgoing UDP packet
  protected int pack( DatagramPacket p ) {
    byte[] buf = p.getData();
    int off = UDP.SZ_TASK;            // Skip udp byte and port and task#
    // Class loader first.  3 bytes of null for system loader.
    Class clazz = _dt.getClass();  // The exact classname to execute
    ClassLoader cl = clazz.getClassLoader();
    if( cl != null && false/*cl instanceof JarLoader*/ ) {
      throw new Error("unimplemented");
      //off = cl._jarkey.write(buf,off); // Write the Key for the ValueCode jar file
    } else {
      buf[off++] = 0; // zero RF
      off += UDP.set2(buf,off,0); // 2 bytes of jarkey length
    }
    // Class name now
    String sclazz = clazz.getName();  // The exact classname to execute
    off += UDP.set2(buf,off,sclazz.length());  // String length
    sclazz.getBytes(0,sclazz.length(),buf,off); // Dump the string also
    off += sclazz.length();
    // Then the args key
    off = _args.write(buf,off);
    // Then the instance data.
    if( _dt.wire_len()+off <= MultiCast.MTU ) {
      off = _dt.write(buf,off);
    } else {
      // Big object, switch to TCP style comms.
      throw new Error("unimplemented: initial send of a large DRemoteTask?");
    }
    return off;
  }

  // Handle the remote-side incoming UDP packet.  This is called on the REMOTE
  // Node, not local.
  public static class RemoteHandler extends UDP {
    // Received a request for N keys.  Build & return the answer.
    void call(DatagramPacket p, H2ONode h2o) {
      // Unpack the incoming arguments
      byte[] buf = p.getData();
      UDP.clr_port(buf); // Re-using UDP packet, so side-step the port reset assert
      int off = UDP.SZ_TASK;          // Skip udp byte and port and task#
      // Unpack the class loader first
      Key classloader_key;
      if( buf[off]==0 && UDP.get2(buf,off+1)==0 ) {
        classloader_key = null; // System loader
        off += 3;
      } else {
        classloader_key = Key.read(buf,off); // Key for the jar file - really a ClassLoader
        off += classloader_key.wire_len();
      }
      // Now the class string name
      int len = get2(buf,off);  off += 2; // Class string length
      String clazz = new String(buf,off,len);
      off += len;               // Skip string
      // Then the args key
      Key args = Key.read(buf,off);
      off += args.wire_len();
      // Make a remote instance of this dude
      RemoteTask dt = RemoteTask.make(classloader_key,clazz);
      // Fill in any fields
      if( dt.wire_len()+off <= MultiCast.MTU ) {
        dt.read(buf,off);
      } else {
        // Big object, switch to TCP style comms.
        throw new Error("unimplemented: initial receive of a large DRemoteTask?");
      }

      // Now compute on it!
      dt.rexec(args);

      // Send it back
      off = UDP.SZ_TASK;        // Skip udp byte and port and task#
      if( dt.wire_len()+off <= MultiCast.MTU ) {
        buf[off++] = 0;         // Result coming via UDP
        off = dt.write(buf,off); // Result
      } else {
        buf[off++] = 1;         // Result coming via UDP
        // Push the large result back *now* (no async pause) via TCP
        if( !tcp_send(h2o,UDP.udp.rexec,get_task(buf),dt) )
          return; // If the TCP failed... then so do we; no result; caller will retry
      }

      reply(p,off,h2o);
    }

    // TCP large DRemoteTask RECEIVE of results.  Note that 'this' is NOT the
    // TaskRemExec object that is hoping to get the received object, nor is the
    // current thread the TRE thread blocking for the object.  The current
    // thread is the TCP reader thread.
    void tcp_read_call( DataInputStream dis, H2ONode h2o ) throws IOException {
      // Read all the parts
      int tnum = dis.readInt();

      // Get the TGK we're waiting on
      TaskRemExec tre = (TaskRemExec)TASKS.get(tnum);
      // Race with canceling a large Value fetch: Task is already dead.  Do not
      // bother reading from the TCP socket, just bail out & close socket.
      if( tre == null ) return;

      // Big Read of Big Results
      tre._dt.read(dis);
      // Here we have the result, and we're on the correct Node but wrong
      // Thread.  If we just return, the TCP reader thread will toss back a TCP
      // ack to the remote, the remote will UDP ACK the TaskRemExec back, and
      // back on the current Node but in the correct Thread, we'd wake up and
      // realize we received a large result.  In theory we could call
      // 'tre.response()' right now, enabling this Node without the UDP packet
      // hop-hop... optimize me Some Day.
    }

    // Pretty-print bytes 1-15; byte 0 is the udp_type enum
    public String print16( byte[] buf ) {
      int udp     = get_ctrl(buf);
      int port    = get_port(buf);
      int tasknum = get_task(buf);
      int off     = UDP.SZ_TASK; // Skip udp byte and port and task#
      byte rf     = buf[off++];            //  8
      int klen    = get2(buf,off); off+=2; // 10
      return "task# "+tasknum+" key["+klen+"]="+new String(buf,10,6);
    }
  }

  // Unpack the answer
  protected RemoteTask unpack( DatagramPacket p ) {
    // Cleanup after thyself
    if( _did_put ) DKV.remove(_args);
    // First SZ_TASK bytes have UDP type# and port# and task#.
    byte[] buf = p.getData();
    int off = UDP.SZ_TASK;      // Skip udp byte and port and task#
    // Read object off the wires
    if( buf[off++] == 0 ) {     // Result is coming via TCP or UDP?
      _dt.read(buf,off);        // UDP result
    } else {
      // Big object, switch to TCP style comms.  Should have already done a
      // DRemoteTask read from the TCP receiver thread... so no need to read here.
    }
    return _dt;
  }

}
