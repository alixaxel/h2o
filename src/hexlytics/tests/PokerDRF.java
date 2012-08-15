package hexlytics.tests;

import hexlytics.Tree;
import hexlytics.RFBuilder.Director;
import hexlytics.RFBuilder.Message;
import hexlytics.RFBuilder.Message.ValidationError;
import hexlytics.RFBuilder.TreeBuilder;
import hexlytics.RFBuilder.TreeValidator;
import hexlytics.data.Data;
import hexlytics.data.DataAdapter;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import water.DKV;
import water.DRemoteTask;
import water.H2O;
import water.H2ONode;
import water.Key;
import water.RemoteTask;
import water.Value;
import water.ValueArray;
import water.csv.CSVParserKV;

/**
 * Distributed RF implementation for poker Data set.
 * 
 * @author tomas
 */
public class PokerDRF extends DRemoteTask implements Director {

  private static final long serialVersionUID = 1976547559782826435L;

  private static String _nodePrefix = null;
  public static final int _nodeId = H2O.CLOUD.nidx(H2O.SELF);;
  final static int _nNodes = H2O.CLOUD._memary.length;

  TreeBuilder _treeBldr;
  final Key[] _compKeys = new Key[_nNodes];
  int _totalTrees;
  long _error; // total number of misclassified records (from validation data)
  long _nrecords; // number of validation records

  public static String nodePrefix(int nodeIdx) {
    return "P" + _nodePrefix + "N" + nodeIdx + "_";
  }

  public static String webrun(Key k, int n) {
    PokerDRF pkr = new PokerDRF(k, n, "[" + (int) (1000000 * Math.random()) + "]_");
    long t = System.currentTimeMillis();
    pkr.doRun();
    return "DRF computed. " + pkr._nrecords + " records processed in " + ellapsed(t) + 
        " seconds, error = " + (double) pkr._error / (double) pkr._nrecords;
    
  }

  static void sleep() { try {  Thread.sleep(1000); } catch (InterruptedException e) { } }  
  static long ellapsed(long t) { return ((System.currentTimeMillis() - t) / 1000); }

  public PokerDRF() { }
 
  public PokerDRF(Key k, int ntrees, String keyPrefix) {
    _totalTrees = ntrees;
    int nTreesPerNode = _totalTrees / _nNodes;
    int kIdx = 0;
    for (H2ONode node : H2O.CLOUD._memary) {
      // home each key to designated computation node
      _compKeys[kIdx] = Key.make("PokerRF" + kIdx, (byte) 1, Key.DFJ_INTERNAL_USER, node);
      // store the keys driving (distributing) the application
     new Message.Init(keyPrefix, _compKeys[kIdx++], k, nTreesPerNode, ntrees).send();
    }
  }

  class ProgressMonitor extends Thread {
    volatile boolean _done;

    public void run() {
      while (!_done) {
        System.out.println(Message.Text.readNext());  PokerDRF.sleep();
      }
    }
  }

  public void doRun() {
    long startTime = System.currentTimeMillis();
    ProgressMonitor progress = new ProgressMonitor();
    progress.start();
    rexec(_compKeys);
    progress._done = true;
    // read the errors
    long errors = 0, nrecords = 0;
    for (int i = 0; i < _nNodes; ++i) {
      ValidationError err = ValidationError.readFrom(i);
      if (err == null)
        System.err.println("Error: missing error report from node " + i);
      else {
        errors += err.err_;
        nrecords += err.nrecords_;
      }
    }
    _nrecords = nrecords;
    _error = errors;
    System.out.println("DRF computed. " + nrecords + " records processed in "
        + ellapsed(startTime) + " seconds, error = " + errors/(double) nrecords);
  }

  public class PokerValidator extends Thread {
    Data _data;

    public PokerValidator(Data data) {  _data = data; }

    /** Get trees one by one and validate them on given data. */
    public void run() {
      TreeValidator validator = new TreeValidator(_data, PokerDRF.this);
      while (validator.rf_.trees().size() < _totalTrees) {
        Message.Tree msg = Message.Tree.readNext();
        if (msg == null) { PokerDRF.sleep();  continue; }
        validator.validate(msg.tree_);
      }
      validator.terminate();
    }
  }

  @Override
  public void map(Key k) {
    String[] cols =  new String[] { "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10" };
    DataAdapter builderData = new DataAdapter("poker",cols, "10", 10);
    DataAdapter validatorData = new DataAdapter("poker", cols, "10", 10);
    Message.Init initMsg = Message.Init.read(k);
    int[] r = new int[11];
    _nodePrefix = initMsg._nodePrefix;
    _totalTrees = initMsg._totalTrees;

    Value dataRootValue = DKV.get(Key.make(initMsg._kb));
    double[] v = new double[11];
    report("Parsing...");
    int recCounter = 0;
    for (long i = 0; i < dataRootValue.chunks(); ++i) {
      CSVParserKV<int[]> p1 = null;
      Value val = null;
      if (dataRootValue instanceof ValueArray) {
        Key chunk = dataRootValue.chunk_get(i);
        if (!chunk.home()) continue; // only compute our own keys
        val = DKV.get(chunk);
        if (val == null) continue;
        p1 = new CSVParserKV<int[]>(chunk, 1, r, null);
      } else {
        p1 = new CSVParserKV<int[]>(dataRootValue.get(), r, null);
      }
      for (int[] x : p1) {
        for (int j = 0; j < 11; j++) v[j] = x[j];
        ++_nrecords;
        if (++recCounter % 3 != 0)
          builderData.addRow(v);
        else {
          validatorData.addRow(v);          
        }
      }
      if (val != null) val.free_mem(); // remove the csv src from mem
    }
    builderData.freeze();
    validatorData.freeze();
    report("Read "+_nrecords+" rows.\nShrinking the data");
    Data bD = Data.make(builderData.shrinkWrap());
    Thread val = new PokerValidator(Data.make(validatorData.shrinkWrap()));
    val.start();    
    _treeBldr = new TreeBuilder(bD, this, initMsg._nTrees);
    _treeBldr.run();
    // wait for the validator to finish
    try{ val.join(); }catch( InterruptedException _){ }
  }

  public void onTreeBuilt(Tree tree) {
    new Message.Tree(_treeBldr.size(), tree).send();
  }

  public void report(String what) {
    new Message.Text(what).send();
  }

  public String nodeName() {
    return "Node" + _nodeId;
  }

  public void error(long error) {    
    new Message.ValidationError(error, _nrecords).send();    
  }

  UnsupportedOperationException uoe() { return new UnsupportedOperationException("Not supported yet."); }
  public void reduce(RemoteTask drt) {/*throw uoe();*/ }
  protected int wire_len() { /*throw uoe();*/ return 0; }
  protected int write(byte[] buf, int off) { /*throw uoe();*/ return off; }
  @Override protected void write(DataOutputStream dos) throws IOException { /*throw uoe();*/ }
  @Override protected void read(byte[] buf, int off) { /*throw uoe();*/ }
  @Override protected void read(DataInputStream dis) throws IOException { /*throw uoe();*/ }
}
