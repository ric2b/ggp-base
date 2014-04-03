package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.player.gamer.statemachine.sancho.heuristic.Heuristic;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

class GameSearcher implements Runnable, ActivityController
{
  private volatile long         moveTime;
  private volatile long         startTime;
  private volatile int          searchSeqRequested  = 0;
  private volatile int          searchSeqProcessing = 0;
  private volatile boolean      stopRequested       = true;
  private volatile boolean      running             = false;
  private int                   numIterations       = 0;
  private volatile boolean      requestYield        = false;
  private MCTSTree              mctsTree            = null;
  private NodePool              nodePool;
  private RolloutProcessorPool  rolloutPool         = null;
  private Heuristic             mHeuristic          = null;
  private double                minExplorationBias  = 0.5;
  private double                maxExplorationBias  = 1.2;

  public GameSearcher(int nodeTableSize)
  {
    nodePool = new NodePool(nodeTableSize);
  }

  public void setHeuristic(Heuristic heuristic)
  {
    mHeuristic = heuristic;
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
                    boolean disableGreedyRollouts) throws GoalDefinitionException
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
    mctsTree = new MCTSTree(underlyingStateMachine,
                            nodePool,
                            roleOrdering,
                            rolloutPool,
                            gameCharacteristics,
                            mHeuristic);

    mctsTree.root = mctsTree.allocateNode(underlyingStateMachine, initialState, null);
    mctsTree.root.decidingRoleIndex = underlyingStateMachine.getRoles().size() - 1;
  }


  @Override
  public void run()
  {
    // TODO Auto-generated method stub
    try
    {
      while (searchAvailable())
      {
        try
        {
          boolean complete = false;

          System.out.println("Move search started");
          //int validationCount = 0;

          //Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

          while (!complete && !stopRequested)
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
              mctsTree.gameCharacteristics.setExplorationBias(maxExplorationBias -
                                                     percentThroughTurn *
                                                     (maxExplorationBias - minExplorationBias) /
                                                     100);
              complete = expandSearch();

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
  }

  public boolean isComplete()
  {
    return mctsTree.root.complete;
  }

  public Move getBestMove()
  {
    return mctsTree.getBestMove();
  }

  public boolean expandSearch() throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException, InterruptedException
  {
    return mctsTree.growTree();
  }

  public int getNumIterations()
  {
    return numIterations;
  }

  private boolean searchAvailable() throws InterruptedException
  {
    synchronized (this)
    {
      if (searchSeqRequested == searchSeqProcessing || stopRequested)
      {
        running = false;
        this.notify();
        this.wait();
      }

      searchSeqProcessing = searchSeqRequested;
      running = true;
    }

    return true;
  }

  public void startSearch(long moveTimeout,
                          ForwardDeadReckonInternalMachineState startState) throws GoalDefinitionException
  {
    mctsTree.setRootState(startState);

    System.out.println("Start move search...");
    synchronized (this)
    {
      rolloutPool.lowestRolloutScoreSeen = 1000;
      rolloutPool.highestRolloutScoreSeen = -100;

      moveTime = moveTimeout;
      startTime = System.currentTimeMillis();
      searchSeqRequested++;
      stopRequested = false;
      numIterations = 0;

      mHeuristic.newTurn(mctsTree.root.state, mctsTree.root);

      this.notify();
    }
  }

  public void stop() throws InterruptedException
  {
    synchronized (this)
    {
      stopRequested = true;

      if (running)
      {
        wait();
      }
    }

    if (rolloutPool != null)
    {
      rolloutPool.stop();
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
    return mctsTree.getSerializationObject();
  }
}