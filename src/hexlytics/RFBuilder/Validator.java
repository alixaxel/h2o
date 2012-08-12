package hexlytics.RFBuilder;

import hexlytics.RandomForest;
import hexlytics.Tree;
import hexlytics.data.Data;
import hexlytics.data.Data.Row;

import java.util.concurrent.LinkedBlockingQueue;

/**
 *
 * @author peta
 */
public class Validator implements Runnable {
  
  final Data data_;
  final BuilderGlue glue_;
  RandomForest rf_;
  final LinkedBlockingQueue<Tree> trees_ = new LinkedBlockingQueue();
  
  private final static Tree TERMINATE = new Tree();    
  private volatile boolean terminate_ = false;
  private int runningThreads_ = 0;
  private Thread[] threads_ = null;
  
  public Validator(Data data, BuilderGlue glue) {
    data_ = data;
    glue_ = glue;
    rf_= new RandomForest(data_,glue_,Integer.MAX_VALUE);
  }
  
  /** Adds the given tree to the queue of trees to be validated. */
  public double validateTree(Tree tree) {
    return rf_.validate(tree);
  }
  
  public void start() {
    run();
  }
  
  public void start(int threads) {
    threads_ = new Thread[threads];
    for (int i = 0; i<threads; ++i) {
      threads_[i] = new Thread(this);
      threads_[i].start();
    }
    
  }

  /** Terminate all threads of the validator. */
  public void terminate() {
    for (int i = 0; i<runningThreads_; ++i)
      trees_.offer(TERMINATE); // to make sure all threads will die
    terminate_ = true;
  }
    
  /** Get trees one by one and validate them on given data. */
  public void run() {
    // increase the number of workers
    synchronized (this) {
      ++runningThreads_;
    }
    int[] errorRows = new int[data_.rows()];
    // get the tree and validate it on given data
    while (true) {
      Tree tree;
      try {
        tree = trees_.take();
        if ((tree == TERMINATE) || (terminate_ == true))
          break;
        // we have a correct tree, validate it on data
        int errors = 0;
        for (Row r : data_) {
          if (tree.classify(r)!=r.classOf)
            errorRows[errors++] = data_.originalIndex(r.index);  
        }
        int[] eRows = new int[errors];
        System.arraycopy(errorRows,0,eRows,0,errors);
        glue_.onTreeValidated(tree, data_.rows(), eRows);
      } catch( InterruptedException ex ) {
        // pass
      }
    }
    // only the last builder to terminate should call the terminated event on
    // the glue object
    boolean isLast;
    synchronized (this) {
      isLast = (--runningThreads_ == 0);
    }
    if (isLast)
      glue_.onValidatorTerminated();
  }
}
