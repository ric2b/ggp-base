package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.HashSet;
import java.util.Set;

import org.ggp.base.player.gamer.statemachine.sancho.TreeNode.TreeNodeAllocator;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.Heuristic;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.Factor;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class GameSearcher implements Runnable, ActivityController
{
  private static final int                PIPELINE_SIZE = 12; //  Default set to give reasonable results on 2 and 4 cores
  private final boolean                   useSearchThreadToRolloutWhenBlocked = true;

  private volatile long                   moveTime;
  private volatile long                   startTime;
  private volatile int                    searchSeqRequested  = 0;
  private volatile int                    searchSeqProcessing = 0;
  private int                             numIterations       = 0;
  private volatile boolean                requestYield        = false;
  private Set<MCTSTree>                   factorTrees         = new HashSet<>();
  private CappedPool<TreeNode>            nodePool;
  private RolloutProcessorPool            rolloutPool         = null;
  private double                          minExplorationBias  = 0.5;
  private double                          maxExplorationBias  = 1.2;
  private volatile boolean                mTerminateRequested = false;
  private RuntimeGameCharacteristics      mGameCharacteristics;
  private Pipeline                        mPipeline;
  private long                            mNumIterations      = 0;
  private long                            mBlockedFor         = 0;

  /**
   * The highest score seen in the current turn (for our role).
   */
  public int                              highestRolloutScoreSeen;

  public long longestObservedLatency = 0;
  public long averageLatency = 0;
  private long numCompletedRollouts = 0;

  /**
   * The lowest score seen in the current turn (for our role).
   */
  public int                              lowestRolloutScoreSeen;

  public GameSearcher(int nodeTableSize)
  {
    nodePool = new CappedPool<>(nodeTableSize);
  }

  public void setExplorationBiasRange(double min, double max)
  {
    minExplorationBias = min;
    maxExplorationBias = max;

    System.out.println("Set explorationBias range to [" + minExplorationBias +
                       ", " + maxExplorationBias + "]");
  }

  public void setup(ForwardDeadReckonPropnetStateMachine underlyingStateMachine,
                    ForwardDeadReckonInternalMachineState initialState,
                    RoleOrdering roleOrdering,
                    RuntimeGameCharacteristics gameCharacteristics,
                    boolean disableGreedyRollouts,
                    Heuristic heuristic) throws GoalDefinitionException
  {
    mGameCharacteristics = gameCharacteristics;

    if (ThreadControl.ROLLOUT_THREADS > 0)
    {
      mPipeline = new Pipeline(PIPELINE_SIZE, underlyingStateMachine.getRoles().size());
    }

    rolloutPool = new RolloutProcessorPool(mPipeline, underlyingStateMachine, mGameCharacteristics, roleOrdering);

    if ( disableGreedyRollouts )
    {
      System.out.println("Disabling greedy rollouts");
      underlyingStateMachine.disableGreedyRollouts();
      rolloutPool.disableGreedyRollouts();
    }

    nodePool.clear(new TreeNodeAllocator(null), false);
    factorTrees.clear();

    Set<Factor> factors = underlyingStateMachine.getFactors();
    if ( factors == null )
    {
      factorTrees.add(new MCTSTree(underlyingStateMachine,
                                   null,
                                   nodePool,
                                   roleOrdering,
                                   rolloutPool,
                                   gameCharacteristics,
                                   heuristic,
                                   this));
    }
    else
    {
      for(Factor factor : factors)
      {
        factorTrees.add(new MCTSTree(underlyingStateMachine,
                                     factor,
                                     nodePool,
                                     roleOrdering,
                                     rolloutPool,
                                     gameCharacteristics,
                                     heuristic.createIndependentInstance(),
                                     this));
      }
    }

    for(MCTSTree tree : factorTrees)
    {
      tree.root = tree.allocateNode(underlyingStateMachine, initialState, null, false);
      tree.root.decidingRoleIndex = underlyingStateMachine.getRoles().size() - 1;
    }
  }


  @Override
  public void run()
  {
    // Register this thread.
    ThreadControl.registerSearchThread();

    // TODO Auto-generated method stub
    try
    {
      while (searchAvailable() && (!mTerminateRequested))
      {
        try
        {
          boolean complete = false;

          System.out.println("Move search started");

          while (!complete && !mTerminateRequested)
          {
            long time = System.currentTimeMillis();
            double percentThroughTurn = Math.min(100, (time - startTime) * 100 / (moveTime - startTime));

            if (requestYield)
            {
              Thread.yield();
            }
            else
            {
              synchronized(getSerializationObject())
              {
                //  Must re-test for a termination request having obtained the lock
                if (!mTerminateRequested)
                {
                  for (MCTSTree tree : factorTrees)
                  {
                    tree.gameCharacteristics.setExplorationBias(maxExplorationBias -
                                                                percentThroughTurn *
                                                                (maxExplorationBias - minExplorationBias) /
                                                                100);
                  }
                  complete = expandSearch(false);
                }
              }
            }
          }

          System.out.println("Move search complete");
        }
        catch (TransitionDefinitionException | MoveDefinitionException | GoalDefinitionException e)
        {
          e.printStackTrace();
        }
      }
    }
    catch (InterruptedException e)
    {
      e.printStackTrace();
    }

    System.out.println("Terminating GameSearcher");
  }

  public boolean isComplete()
  {
    for(MCTSTree tree : factorTrees)
    {
      if ( !tree.root.complete )
      {
        return false;
      }
    }

    return true;
  }

  public Move getBestMove()
  {
    synchronized(getSerializationObject())
    {
      FactorMoveChoiceInfo bestChoice = null;

      System.out.println("Num tree node frees: " + nodePool.getNumFreedItems());
      System.out.println("Num tree nodes currently in use: " + nodePool.getNumUsedItems());
      System.out.println("Searching for best move amongst factors:");
      for(MCTSTree tree : factorTrees)
      {
        FactorMoveChoiceInfo factorChoice = tree.getBestMove();
        if ( factorChoice.bestMove != null )
        {
          System.out.println("  Factor best move: " + factorChoice.bestMove);

          if ( bestChoice == null )
          {
            bestChoice = factorChoice;
          }
          else
          {
            if ( factorChoice.pseudoNoopValue <= 0 && factorChoice.pseudoMoveIsComplete &&
                 factorChoice.bestMoveValue > 0 &&
                 (!bestChoice.pseudoMoveIsComplete || bestChoice.pseudoNoopValue > 0) )
            {
              //  If no-oping this factor is a certain loss but the same is not true of the other
              //  factor then take this factor
              System.out.println("  Factor move is avoids a loss so selecting");
              bestChoice = factorChoice;
            }
            // Complete win dominates everything else
            else if ( factorChoice.bestMoveValue == 100 )
            {
              System.out.println("  Factor move is a win so selecting");
              bestChoice = factorChoice;
            }
            else if ( (bestChoice.bestMoveValue == 100 && bestChoice.bestMoveIsComplete) ||
                      (factorChoice.bestMoveValue <= 0 && factorChoice.bestMoveIsComplete) )
            {
              System.out.println("  Already selected factor move is a win or this move is a loss - not selecting");
              continue;
            }
            // otherwise choose the one that reduces the resulting net chances the least weighted
            // by the resulting win chance in the chosen factor.  This biases the player towards
            // concentrating on the factor it is most ahead in, and is somewhat experimental (since ignoring
            // the factor you are behind in could be rather bad too!)
            else
            {
              if ( bestChoice.bestMoveValue*(bestChoice.bestMoveValue - bestChoice.pseudoNoopValue) <
                   factorChoice.bestMoveValue*(factorChoice.bestMoveValue - factorChoice.pseudoNoopValue))
              {
                bestChoice = factorChoice;
                System.out.println("  This factor score is superior - selecting");
              }
              else
              {
                System.out.println("  This factor score is inferior - not selecting");
              }
            }
          }
        }
        else
        {
          System.out.println("  Factor best move is NULL");
        }

        //tree.root.dumpTree("c:\\temp\\treeDump_factor" + factorIndex + ".txt");
      }

      assert(bestChoice != null);
      return bestChoice.bestMove;
    }
  }

  public boolean expandSearch(boolean forceSynchronous) throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
    boolean lAllTreesCompletelyExplored;

    numIterations++;
    while (nodePool.isFull())
    {
      boolean somethingDisposed = false;

      for(MCTSTree tree : factorTrees)
      {
        if (!tree.root.complete)
        {
          //  The trees may have very asymmetric sizes due to one being nearly
          //  complete, in which case it is possible that no candidates for trimming
          //  will be found.  This should not be possible in all trees if the node pool
          //  is nearly full, so check that at least one tree does release something
          somethingDisposed |= tree.root.disposeLeastLikelyNode();
        }
      }

      assert(somethingDisposed);
    }

    lAllTreesCompletelyExplored = true;
    for (MCTSTree tree : factorTrees)
    {
      if (!tree.root.complete)
      {
        if (ThreadControl.ROLLOUT_THREADS > 0 && !forceSynchronous)
        {
          // If there's back-propagation work to do, do it now, in preference to more select/expand cycles because the
          // back-propagation will mean that subsequent select/expand cycles are more accurate.
          processCompletedRollouts(false);
        }

        // Perform an MCTS iteration.
        lAllTreesCompletelyExplored &= tree.growTree(forceSynchronous);
      }
    }

    return lAllTreesCompletelyExplored;
  }

  public int getNumIterations()
  {
    return numIterations;
  }

  public int getNumRollouts()
  {
    int result = 0;

    for (MCTSTree tree : factorTrees)
    {
      result += tree.numNonTerminalRollouts;
    }

    return result;
  }

  private boolean searchAvailable() throws InterruptedException
  {
    synchronized (this)
    {
      if (searchSeqRequested == searchSeqProcessing)
      {
        this.notifyAll();
        this.wait();
      }

      searchSeqProcessing = searchSeqRequested;
    }

    return true;
  }

  /**
   * @return the pipeline being used by this game searcher.
   */
  public Pipeline getPipeline()
  {
    return mPipeline;
  }

  public void startSearch(long moveTimeout,
                          ForwardDeadReckonInternalMachineState startState) throws GoalDefinitionException
  {

    // Print out some statistics from last turn.
    System.out.println("Last time...");
    System.out.println("  Number of MCTS iterations: " + mNumIterations);
    if ( !useSearchThreadToRolloutWhenBlocked )
    {
      System.out.println("  Tree thread blocked for:   " + mBlockedFor / 1000000 + "ms");
      mBlockedFor = 0;
    }
    mNumIterations = 0;

    System.out.println("Start move search...");
    synchronized (this)
    {
      for(MCTSTree tree : factorTrees)
      {
        tree.setRootState(startState);
      }

      lowestRolloutScoreSeen = 1000;
      highestRolloutScoreSeen = -100;

      moveTime = moveTimeout;
      startTime = System.currentTimeMillis();
      searchSeqRequested++;
      numIterations = 0;

      this.notify();
    }
  }

  @Override
  public void requestYield(boolean state)
  {
    // !! ARR This is a bit course.  We could pass more useful messages about the desired state (including whether we
    // !! ARR should be spinning through outstanding work from a previous turn, whether this is an emergency stop
    // !! ARR request, etc.)
    requestYield = state;
  }

  @Override
  public Object getSerializationObject()
  {
    // !! ARR Who locks against us (Sancho thread) and why (to start a new turn and all that entails + termination)?
    // !! ARR But can we do better and not have any other threads needing to access the tree / state machine / etc.?
    return this;
  }

  void processCompletedRollouts(boolean xiNeedToDoOne) throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
    boolean canBackPropagate;

    while ((canBackPropagate = mPipeline.canBackPropagate()) || xiNeedToDoOne)
    {
      if (!useSearchThreadToRolloutWhenBlocked)
      {
        if (xiNeedToDoOne)
        {
          mBlockedFor -= System.nanoTime();
        }
      }

      if ( useSearchThreadToRolloutWhenBlocked )
      {
        if (xiNeedToDoOne && !canBackPropagate)
        {
          //  If the rollout threads are not keeping up and the pipeline
          //  is full then perform an expansion synchronously while we
          //  wait for the rollout pool to have results for us
          expandSearch(true);
          continue;
        }
      }

      RolloutRequest lRequest = mPipeline.getNextRequestForBackPropagation();

      if (!useSearchThreadToRolloutWhenBlocked)
      {
        if (xiNeedToDoOne)
        {
          mBlockedFor += System.nanoTime();
        }
      }

      if ( longestObservedLatency < lRequest.mQueueLatency )
      {
        longestObservedLatency = lRequest.mQueueLatency;
      }
      averageLatency = (averageLatency*numCompletedRollouts + lRequest.mQueueLatency)/(numCompletedRollouts+1);
      numCompletedRollouts++;

      // Update min/max scores.
      if (lRequest.mMaxScore > highestRolloutScoreSeen)
      {
        highestRolloutScoreSeen = lRequest.mMaxScore;
      }

      if (lRequest.mMinScore < lowestRolloutScoreSeen)
      {
        lowestRolloutScoreSeen = lRequest.mMinScore;
      }

      //masterMoveWeights.accumulate(request.playedMoveWeights);

      TreeNode node = lRequest.mNode.node;
      if (lRequest.mNode.seq == node.seq && !node.complete)
      {
        lRequest.mPath.resetCursor();
        node.updateStats(lRequest.mAverageScores,
                         lRequest.mAverageSquaredScores,
                         lRequest.mSampleSize,
                         lRequest.mPath,
                         false);
      }

      mPipeline.completedBackPropagation();
      xiNeedToDoOne = false;
      mNumIterations++;
    }
  }

  public void terminate()
  {
    synchronized(getSerializationObject())
    {
      if (rolloutPool != null)
      {
        rolloutPool.stop();
        rolloutPool = null;
      }

      mTerminateRequested = true;
      notifyAll();
    }
  }
}