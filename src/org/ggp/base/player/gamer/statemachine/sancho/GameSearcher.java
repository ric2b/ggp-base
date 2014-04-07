package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.HashSet;
import java.util.Set;

import org.ggp.base.player.gamer.statemachine.sancho.heuristic.Heuristic;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.Factor;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

class GameSearcher implements Runnable, ActivityController
{
  private volatile long         moveTime;
  private volatile long         startTime;
  private volatile int          searchSeqRequested  = 0;
  private volatile int          searchSeqProcessing = 0;
  private int                   numIterations       = 0;
  private volatile boolean      requestYield        = false;
  private Set<MCTSTree>         factorTrees         = new HashSet<>();
  private NodePool              nodePool;
  private RolloutProcessorPool  rolloutPool         = null;
  private double                minExplorationBias  = 0.5;
  private double                maxExplorationBias  = 1.2;
  private volatile boolean      mTerminateRequested = false;

  public GameSearcher(int nodeTableSize)
  {
    nodePool = new NodePool(nodeTableSize);
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
                    int numRolloutThreads,
                    boolean disableGreedyRollouts,
                    Heuristic heuristic) throws GoalDefinitionException
  {
    rolloutPool = new RolloutProcessorPool(numRolloutThreads, underlyingStateMachine, roleOrdering.roleIndexToRole(0));
    rolloutPool.setRoleOrdering(roleOrdering);

    if ( disableGreedyRollouts )
    {
      System.out.println("Disabling greedy rollouts");
      underlyingStateMachine.disableGreedyRollouts();
      rolloutPool.disableGreedyRollouts();
    }

    nodePool.clear(null);
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
                                    heuristic));
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
                                     heuristic.createIndependentInstance()));
      }
    }

    for(MCTSTree tree : factorTrees)
    {
      tree.root = tree.allocateNode(underlyingStateMachine, initialState, null);
      tree.root.decidingRoleIndex = underlyingStateMachine.getRoles().size() - 1;
    }
  }


  @Override
  public void run()
  {
    // TODO Auto-generated method stub
    try
    {
      while (searchAvailable() && (!mTerminateRequested))
      {
        try
        {
          boolean complete = false;

          System.out.println("Move search started");
          //int validationCount = 0;

          //Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

          while (!complete && !mTerminateRequested)
          {
            long time = System.currentTimeMillis();
            double percentThroughTurn = Math
                .min(100, (time - startTime) * 100 / (moveTime - startTime));

            //							if ( Math.abs(lastPercentThroughTurn - percentThroughTurn) > 4 )
            //							{
            //								System.out.println("Percent through turn: " + percentThroughTurn + " - num iterations: " + numIterations + ", root has children=" + (root.children != null));
            //								lastPercentThroughTurn = percentThroughTurn;
            //							}
            if (requestYield)
            {
              Thread.yield();
            }
            else
            {
              synchronized(getSerializationObject())
              {
                //  Must re-test for a termination request having obtained the lock
                if ( !mTerminateRequested )
                {
                  for(MCTSTree tree : factorTrees)
                  {
                    tree.gameCharacteristics.setExplorationBias(maxExplorationBias -
                                                           percentThroughTurn *
                                                           (maxExplorationBias - minExplorationBias) /
                                                           100);
                  }
                  complete = expandSearch();
                }
              }
            }
          }

          System.out.println("Move search complete");
        }
        catch (TransitionDefinitionException | MoveDefinitionException
            | GoalDefinitionException e)
        {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }
    }
    catch (InterruptedException e)
    {
      // TODO Auto-generated catch block
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
      Move result = null;
      int factorIndex = 0;

      System.out.println("Num tree node frees: " + nodePool.getNumFreedNodes());
      System.out.println("Num tree nodes currently in use: " + nodePool.getNumUsedNodes());
      System.out.println("Searching for best move amongst factors:");
      for(MCTSTree tree : factorTrees)
      {
        factorIndex++;
        Move move = tree.getBestMove();
        if ( move != null )
        {
          System.out.println("  Factor best move: " + move);
          result = move;
        }
        else
        {
          System.out.println("  Factor best move is NULL");
        }

        //tree.root.dumpTree("c:\\temp\\treeDump_factor" + factorIndex + ".txt");
      }

      return result;
    }
  }

  public boolean expandSearch() throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException, InterruptedException
  {
    boolean result;

    while (nodePool.isFull())
    {
      boolean somethingDisposed = false;

      for(MCTSTree tree : factorTrees)
      {
        if ( !tree.root.complete )
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

    processCompletedRollouts();

    if (!rolloutPool.isBackedUp())
    {
      result = true;

      for(MCTSTree tree : factorTrees)
      {
        if ( !tree.root.complete )
        {
          result &= tree.growTree();
        }
      }
    }
    else
    {
      result = false;
    }

    return result;
  }

  public int getNumIterations()
  {
    return numIterations;
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

  public void startSearch(long moveTimeout,
                          ForwardDeadReckonInternalMachineState startState) throws GoalDefinitionException
  {
    System.out.println("Start move search...");
    synchronized (this)
    {
      //  Process anything left over from last turn's timeout
      processCompletedRollouts();

      for(MCTSTree tree : factorTrees)
      {
        tree.setRootState(startState);
      }

      rolloutPool.lowestRolloutScoreSeen = 1000;
      rolloutPool.highestRolloutScoreSeen = -100;

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
    requestYield = state;
  }

  @Override
  public Object getSerializationObject()
  {
    return this;
  }

  private void processCompletedRollouts()
  {
    //ProfileSection methodSection = new ProfileSection("processCompletedRollouts");
    //try
    //{
    RolloutRequest request;

    while ((request = rolloutPool.completedRollouts.poll()) != null)
    {
      TreeNode node = request.node.node;

      //masterMoveWeights.accumulate(request.playedMoveWeights);

      if (request.node.seq == node.seq && !node.complete)
      {
        request.path.resetCursor();
        //validateAll();
        node.updateStats(request.averageScores,
                         request.averageSquaredScores,
                         request.sampleSize,
                         request.path,
                         false);
        //validateAll();
      }

      rolloutPool.numCompletedRollouts++;
    }
    //}
    //finally
    //{
    //  methodSection.exitScope();
    //}
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