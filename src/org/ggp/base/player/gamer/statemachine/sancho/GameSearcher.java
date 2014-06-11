package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.ggp.base.player.gamer.statemachine.sancho.StatsLogUtils.Series;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode.TreeNodeAllocator;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.Heuristic;
import org.ggp.base.player.gamer.statemachine.sancho.pool.CappedPool;
import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool;
import org.ggp.base.player.gamer.statemachine.sancho.pool.UncappedPool;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.exceptions.GDLException;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.Factor;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * The thread that searches the game tree.  This is the only thread that can make updates to the game tree except under
 * lock of {@link #getSerializationObject()}.
 */
public class GameSearcher implements Runnable, ActivityController
{
  private static final Logger LOGGER       = LogManager.getLogger();
  private static final Logger STATS_LOGGER = LogManager.getLogger("stats");

  /**
   * Whether the sample size is updated as a result of thread performance measurements.
   */
  public static final boolean             USE_DYNAMIC_SAMPLE_SIZING = true;

  /**
   * The update interval for sample size.
   */
  public static final long                SAMPLE_SIZE_UPDATE_INTERVAL_MS = 10000;

  private static final long               STATS_LOG_INTERVAL_MS = 1000;
  private static final int                PIPELINE_SIZE = 24; //  Default set to give reasonable results on 2 and 4 cores

  private static final boolean            ADJUST_EXPLORATION_BIAS_FROM_TREE_SHAPE = false;

  private volatile long                   moveTime;
  private volatile long                   startTime;
  private volatile int                    searchSeqRequested  = 0;
  private volatile int                    searchSeqProcessing = 0;
  private int                             numIterations       = 0;
  private volatile boolean                requestYield        = false;
  private Set<MCTSTree>                   factorTrees         = new HashSet<>();
  private GamePlan                        mPlan               = null;
  private final CappedPool<TreeNode>      mNodePool;
  private final Pool<TreeEdge>            mEdgePool;
  private final ScoreVectorPool           mScoreVectorPool;
  private RolloutProcessorPool            rolloutPool         = null;
  private double                          minExplorationBias  = 0.5;
  private double                          maxExplorationBias  = 1.2;
  private volatile boolean                mTerminateRequested = false;
  private RuntimeGameCharacteristics      mGameCharacteristics;
  private Pipeline                        mPipeline;
  private long                            mLastNumIterations  = 0;
  private long                            mNumIterations      = 0;
  private int                             mRootDepth          = 0;
  private boolean                         mSuppressSampleSizeUpdate = false;
  private final String                    mLogName;

  private final SampleAverageMean         mAverageFringeDepth = new SampleAverageMean();
  private final SampleAverageRMS          mRMSFringeDepth = new SampleAverageRMS();
  /**
   * Average observed branching factor from ode expansions
   */
  public final SampleAverage              mAverageBranchingFactor = new SampleAverageMean();

  /**
   * The highest score seen in the current turn (for our role).
   */
  public int                              highestRolloutScoreSeen;

  /**
   * The lowest score seen in the current turn (for our role).
   */
  public int                              lowestRolloutScoreSeen;

  /**
   * The last combined performance statistics from all rollout threads.
   */
  private RolloutPerfStats                mLastRolloutPerfStats = new RolloutPerfStats(0, 0);

  /**
   * Longest observed latency for a rollout.  Used by the performance analysis test.
   */
  public long longestObservedLatency = 0;

  /**
   * Average latency.  Used by the performance analysis test.
   */
  public long averageLatency = 0;

  /**
   * Number of completed rollouts.  !! ARR Appears to be a duplicate of mNumIterations?
   */
  private long numCompletedRollouts = 0;

  private double currentExplorationBias;

  /**
   * Create a game tree searcher with the specified maximum number of nodes.
   *
   * @param nodeTableSize - the maximum number of nodes.
   * @param xiLogName     - the name of the log.
   */
  public GameSearcher(int nodeTableSize, int numRoles, String xiLogName)
  {
    mNodePool = new CappedPool<>(nodeTableSize);
    mEdgePool = new UncappedPool<>(nodeTableSize * 2);
    mScoreVectorPool = new ScoreVectorPool(nodeTableSize, numRoles);
    mLogName = xiLogName;
  }

  /**
   * Limit the exploration bias to the specified range.
   *
   * @param min - the minimum exploration bias.
   * @param max - the maximum exploration bias.
   */
  public void setExplorationBiasRange(double min, double max)
  {
    minExplorationBias = min;
    maxExplorationBias = max;
    currentExplorationBias = (min + max)/2;

    LOGGER.info("Set explorationBias range to [" + minExplorationBias + ", " + maxExplorationBias + "]");
  }

  /**
   * Configure the game searcher.  This method must be called before startSearch().
   *
   * @param underlyingStateMachine
   * @param initialState
   * @param roleOrdering
   * @param gameCharacteristics
   * @param disableGreedyRollouts
   * @param heuristic
   * @param plan - pre-prepared set of moves to use (or null for none).
   *
   * @throws GoalDefinitionException
   */
  public void setup(ForwardDeadReckonPropnetStateMachine underlyingStateMachine,
                    ForwardDeadReckonInternalMachineState initialState,
                    RoleOrdering roleOrdering,
                    RuntimeGameCharacteristics gameCharacteristics,
                    boolean disableGreedyRollouts,
                    Heuristic heuristic,
                    GamePlan plan) throws GoalDefinitionException
  {
    mGameCharacteristics = gameCharacteristics;
    mPlan = plan;

    StateInfo.createBuffer(underlyingStateMachine.getRoles().size());

    if (ThreadControl.ROLLOUT_THREADS > 0)
    {
      mPipeline = new Pipeline(PIPELINE_SIZE, underlyingStateMachine.getRoles().size());
    }

    rolloutPool = new RolloutProcessorPool(mPipeline, underlyingStateMachine, roleOrdering, mLogName);

    if (disableGreedyRollouts)
    {
      LOGGER.info("Disabling greedy rollouts");
      underlyingStateMachine.disableGreedyRollouts();
      rolloutPool.disableGreedyRollouts();
    }

    mNodePool.clear(new TreeNodeAllocator(null), false);
    factorTrees.clear();

    Set<Factor> factors = underlyingStateMachine.getFactors();
    if (factors == null)
    {
      factorTrees.add(new MCTSTree(underlyingStateMachine,
                                   null,
                                   mNodePool,
                                   mScoreVectorPool,
                                   mEdgePool,
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
                                     mNodePool,
                                     mScoreVectorPool,
                                     mEdgePool,
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
    ThreadContext.put("matchID", mLogName);
    ThreadControl.registerSearchThread();

    long lNextUpdateSampleSizeTime = 0;
    long lNextStatsTime = System.currentTimeMillis() + STATS_LOG_INTERVAL_MS;

    try
    {
      while (searchAvailable() && (!mTerminateRequested))
      {
        try
        {
          LOGGER.info("Move search started");

          if (lNextUpdateSampleSizeTime == 0)
          {
            LOGGER.info("Starting sample size update timer");
            lNextUpdateSampleSizeTime = System.currentTimeMillis() + SAMPLE_SIZE_UPDATE_INTERVAL_MS;
          }

          boolean complete = false;
          while (!complete && !mTerminateRequested)
          {
            long time = System.currentTimeMillis();

            if (time > lNextStatsTime)
            {
              StringBuffer lLogBuf = new StringBuffer(1024);
              Series.NODE_EXPANSIONS.logDataPoint(lLogBuf, time, mNumIterations);
              Series.POOL_USAGE.logDataPoint(lLogBuf, time, mNodePool.getPoolUsage());

              //Future intent will be to add these to the stats logger when it is stable
              //double fringeDepth = mAverageFringeDepth.getMean();
              //Series.FRINGE_DEPTH.logDataPoint(lLogBuf, time, (long)(fringeDepth+0.5));
              //Series.TREE_ASPECT_RATIO.logDataPoint(lLogBuf, time, (long)(nodePool.getNumUsedItems()/(fringeDepth*fringeDepth)));
              //mAverageFringeDepth.clear();

              STATS_LOGGER.info(lLogBuf.toString());
              lNextStatsTime += STATS_LOG_INTERVAL_MS;

              if ( ADJUST_EXPLORATION_BIAS_FROM_TREE_SHAPE )
              {
                double fringeDepth = mAverageFringeDepth.getAverage();
                double branchingFactor = mAverageBranchingFactor.getAverage();

                //  Adjust the branching factor to the correct geometric mean
                if ( !mGameCharacteristics.isSimultaneousMove )
                {
                  branchingFactor = Math.exp(Math.log(branchingFactor*mGameCharacteristics.numRoles)/mGameCharacteristics.numRoles);
                }
                if ( fringeDepth > 0 && branchingFactor > 0 )
                {
                  //  Calculate the tree aspect ratio, which is the ratio of the observed average fringe
                  //  depth to its expected depth given its observed branching factor
                  double aspect = fringeDepth/(Math.log(mNodePool.getNumItemsInUse())/Math.log(branchingFactor));

                  if ( aspect < 1.2 && currentExplorationBias > 0.2 )//minExplorationBias )
                  {
                    currentExplorationBias /= 1.1;
                    LOGGER.info("Decreasing exploration bias to: " + currentExplorationBias);
                  }
                  else if ( aspect > 1.3 && currentExplorationBias < maxExplorationBias )
                  {
                    currentExplorationBias *= 1.1;
                    LOGGER.info("Increasing exploration bias to: " + currentExplorationBias);
                  }
                }
              }
              else
              {
                double percentThroughTurn = Math.min(100, (time - startTime) * 100 / (moveTime - startTime));
                currentExplorationBias = maxExplorationBias -
                                          percentThroughTurn *
                                          (maxExplorationBias - minExplorationBias) /
                                          100;
              }
            }

            if ((USE_DYNAMIC_SAMPLE_SIZING) && (time > lNextUpdateSampleSizeTime))
            {
              updateSampleSize();
              lNextUpdateSampleSizeTime += SAMPLE_SIZE_UPDATE_INTERVAL_MS;
            }

            if (requestYield)
            {
              Thread.yield();

              // Because this thread has been yielding, it hasn't been filling the rollout pipeline.  Therefore, the
              // performance stats from the rollout threads are meaningless and mustn't be used to update the sample
              // size.
              mSuppressSampleSizeUpdate = true;
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
                    tree.gameCharacteristics.setExplorationBias(currentExplorationBias);
                  }
                  complete = expandSearch(false);
                }
              }
            }
          }

          LOGGER.info("Move search complete");

          // Because this thread has finished searching the game tree, it will no longer fill the rollout pipeline.
          // Therefore, the performance stats from the rollout threads are meaningless and mustn't be used to update the
          // sample size.
          mSuppressSampleSizeUpdate = true;
        }
        catch (GDLException lEx)
        {
          LOGGER.error("GDLException: " + lEx);
          lEx.printStackTrace();
        }
        catch (AssertionError lEx)
        {
          LOGGER.error("AssertionError: " + lEx);
          throw new AssertionError("Rethrown AssertionError", lEx);
        }
      }
    }
    catch (InterruptedException e)
    {
      LOGGER.warn("Game search unexpectedly interrupted");
      e.printStackTrace();
    }

    LOGGER.info("Terminating GameSearcher");
  }

  /**
   * @return whether the search is complete.  The search is complete if it's complete in all factors.
   */
  public boolean isComplete()
  {
    for (MCTSTree tree : factorTrees)
    {
      if (!tree.root.complete)
      {
        return false;
      }
    }

    return true;
  }

  /**
   * @return the best move discovered (from the current root of the tree).
   */
  public Move getBestMove()
  {
    synchronized(getSerializationObject())
    {
      //  If we instated a plan during this move calculation we must play from it
      if (!mPlan.isEmpty())
      {
        Move result = mPlan.nextMove();
        LOGGER.info("Playing first move from new plan: " + result);

        //  No point in further searching
        terminate();
        return result;
      }

      FactorMoveChoiceInfo bestChoice = null;

      //  The following will move to (or also reflect in) the stats logger once it is stable
      double fringeDepth = mAverageFringeDepth.getAverage();
      double RMSFringeDepth = mRMSFringeDepth.getAverage();
      double branchingFactor = mAverageBranchingFactor.getAverage();
      if ( fringeDepth > 0 && branchingFactor > 0 )
      {
        //  Adjust the branching factor to the correct geometric mean
        if ( !mGameCharacteristics.isSimultaneousMove )
        {
          branchingFactor = Math.exp(Math.log(branchingFactor*mGameCharacteristics.numRoles)/mGameCharacteristics.numRoles);
        }
        LOGGER.info("Average fringe depth: " + fringeDepth);
        LOGGER.info("Fringe depth variability: " + (RMSFringeDepth - fringeDepth)/fringeDepth);
        LOGGER.info("Average branching factor: " + branchingFactor);
        //  Calculate the tree aspect ratio, which is the ratio of the observed average fringe
        //  depth to its expected depth given its observed branching factor
        double aspect = fringeDepth/(Math.log(mNodePool.getNumItemsInUse())/Math.log(branchingFactor));
        LOGGER.info("Tree aspect ratio: " + aspect);
      }

      LOGGER.debug("Searching for best move amongst factors:");
      for(MCTSTree tree : factorTrees)
      {
        FactorMoveChoiceInfo factorChoice = tree.getBestMove();
        if (factorChoice.bestMove != null)
        {
          LOGGER.debug("  Factor best move: " + factorChoice.bestMove);

          if (bestChoice == null)
          {
            bestChoice = factorChoice;
          }
          else
          {
            if (factorChoice.pseudoNoopValue <= 0 && factorChoice.pseudoMoveIsComplete &&
                factorChoice.bestMoveValue > 0 &&
                (!bestChoice.pseudoMoveIsComplete || bestChoice.pseudoNoopValue > 0))
            {
              //  If no-oping this factor is a certain loss but the same is not true of the other
              //  factor then take this factor
              LOGGER.debug("  Factor move avoids a loss so selecting");
              bestChoice = factorChoice;
            }
            // Complete win dominates everything else
            else if (factorChoice.bestMoveValue > 99.9)
            {
              LOGGER.debug("  Factor move is a win so selecting");
              bestChoice = factorChoice;
            }
            else if ((bestChoice.bestMoveValue > 99.9 && bestChoice.bestMoveIsComplete) ||
                     (factorChoice.bestMoveValue <= 0.1 && factorChoice.bestMoveIsComplete))
            {
              LOGGER.debug("  Already selected factor move is a win or this move is a loss - not selecting");
              continue;
            }
            // otherwise choose the one that reduces the resulting net chances the least weighted
            // by the resulting win chance in the chosen factor.  This biases the player towards
            // concentrating on the factor it is most ahead in, and is somewhat experimental (since ignoring
            // the factor you are behind in could be rather bad too!)
            else
            {
              if (bestChoice.bestMoveValue*(bestChoice.bestMoveValue - bestChoice.pseudoNoopValue) <
                  factorChoice.bestMoveValue*(factorChoice.bestMoveValue - factorChoice.pseudoNoopValue))
              {
                bestChoice = factorChoice;
                LOGGER.debug("  This factor score is superior - selecting");
              }
              else
              {
                LOGGER.debug("  This factor score is inferior - not selecting");
              }
            }
          }
        }
        else
        {
          LOGGER.warn("  Factor best move is NULL");
        }

        //tree.root.dumpTree("c:\\temp\\treeDump_factor" + factorIndex + ".txt");
      }

      assert(bestChoice != null) : "No move choice found";
      StatsLogUtils.Series.SCORE.logDataPoint((long)Math.max(0, bestChoice.bestMoveValue + 0.5));
      return bestChoice.bestMove;
    }
  }

  /**
   * Expand the search tree by performing a single MCTS iteration across all factor trees.
   *
   * @param forceSynchronous - true if the rollout should be performed synchronously (by this thread).
   *
   * @return whether all factor trees have been completely explored.
   *
   * @throws MoveDefinitionException
   * @throws TransitionDefinitionException
   * @throws GoalDefinitionException
   */
  public boolean expandSearch(boolean forceSynchronous)
    throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
    boolean lAllTreesCompletelyExplored;

    numIterations++;
    while (mNodePool.isFull())
    {
      boolean somethingDisposed = false;

      for (MCTSTree tree : factorTrees)
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

  /**
   * @return the number of iterations performed.
   */
  public int getNumIterations()
  {
    return numIterations;
  }

  /**
   * @return the number of rollouts performed.  (This can be more than the number of iterations if the sample size is,
   * or has been, greater than 1.)
   */
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
        if (!mTerminateRequested)
        {
          this.wait();
        }
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

  /**
   * Start searching the game tree (or re-root the tree at the start of a new turn).
   *
   * @param moveTimeout - the time (in milliseconds) at which a move must be submitted.
   * @param startState  - the state at the root of the tree.
   * @param rootDepth   - the current depth (in turns since the beginning of the game) of the tree root.
   *
   * @throws GoalDefinitionException
   */
  public void startSearch(long moveTimeout,
                          ForwardDeadReckonInternalMachineState startState,
                          short rootDepth) throws GoalDefinitionException
  {

    // Print out some statistics from last turn.
    LOGGER.info("MCTS iterations last turn = " + (mNumIterations - mLastNumIterations));
    mLastNumIterations = mNumIterations;

    LOGGER.debug("Start move search...");
    synchronized (this)
    {
      for(MCTSTree tree : factorTrees)
      {
        tree.setRootState(startState, rootDepth);
      }

      lowestRolloutScoreSeen = 1000;
      highestRolloutScoreSeen = -100;

      moveTime = moveTimeout;
      startTime = System.currentTimeMillis();
      searchSeqRequested++;
      numIterations = 0;
      setRootDepth(rootDepth);

      //  Clear stat averages
      mAverageFringeDepth.clear();
      mRMSFringeDepth.clear();
      mAverageBranchingFactor.clear();

      this.notify();
    }
  }

  @Override
  public void requestYield(boolean state)
  {
    // !! ARR This is a bit coarse.  We could pass more useful messages about the desired state (including whether we
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

  /**
   * Process all completed rollouts in the pipeline.
   *
   * @param xiNeedToDoOne - whether this method should block until at least 1 rollout has been performed.
   *
   * @throws MoveDefinitionException
   * @throws TransitionDefinitionException
   * @throws GoalDefinitionException
   */
  void processCompletedRollouts(boolean xiNeedToDoOne) throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
    boolean canBackPropagate;

    while ((canBackPropagate = mPipeline.canBackPropagate()) || xiNeedToDoOne)
    {
      if (xiNeedToDoOne && !canBackPropagate && mGameCharacteristics.getRolloutSampleSize() == 1)
      {
        //  If the rollout threads are not keeping up and the pipeline
        //  is full then perform an expansion synchronously while we
        //  wait for the rollout pool to have results for us
        expandSearch(true);
        mNumIterations++;

        //  This method can be called re-entrantly from the above, which means that the rollout
        //  pipeline may no longer be blocked, in which case we should return immediately
        if (mPipeline.canExpand())
        {
          return;
        }
        continue;
      }

      RolloutRequest lRequest = mPipeline.getNextRequestForBackPropagation();

      if (longestObservedLatency < lRequest.mQueueLatency)
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

      if (!lRequest.mPath.isFreed())
      {
        TreeNode lNode = TreeNode.get(mNodePool, lRequest.mNodeRef);
        if (lNode != null && !lNode.complete)

        {
          mAverageFringeDepth.addSample(lNode.getDepth() - getRootDepth());
          mRMSFringeDepth.addSample(lNode.getDepth() - getRootDepth());
          lRequest.mPath.resetCursor();

          if (lRequest.mPlayedMovesForWin != null)
          {
            //  First build up the move path to the node that was rolled out from
            List<ForwardDeadReckonLegalMoveInfo> fullPlayoutList = new LinkedList<>();

            while(lRequest.mPath.hasMore())
            {
              lRequest.mPath.getNextNode();
              assert(lRequest.mPath.getCurrentElement() != null);

              TreeEdge edge = lRequest.mPath.getCurrentElement().getEdge();
              fullPlayoutList.add(0, edge.partialMove);
            }

            lRequest.mPath.resetCursor();

            //  Now append the rollout path
            fullPlayoutList.addAll(lRequest.mPlayedMovesForWin);

            //  Provide this winning path for consideration as our new plan
            mPlan.considerPlan(fullPlayoutList);
          }

          lNode.updateStats(lRequest.mAverageScores,
                            lRequest.mAverageSquaredScores,
                            lRequest.mPath,
                            false);
        }
      }

      mPipeline.completedBackPropagation();
      xiNeedToDoOne = false;
      mNumIterations++;
    }
  }

  /**
   * Update the sample size in order to try to keep the search thread busy, because it is the only thread able to access
   * the tree (to avoid hideous locking code) and is therefore usually the bottleneck.
   *
   * Adjust the sample size to keep the rollout threads 80% busy.  This appears to give a happy trade-off between doing
   * as many samples as reasonably possible whilst not leaving the pipeline full (thereby blocking the search thread)
   * for prolonged periods.
   */
  private void updateSampleSize()
  {
    // Get the most recent combined total statistics and calculate the difference from last time round.
    RolloutPerfStats lCombinedStatsTotal = new RolloutPerfStats(mPipeline.getRolloutPerfStats());
    RolloutPerfStats lStatsDiff = lCombinedStatsTotal.getDifference(mLastRolloutPerfStats);
    mLastRolloutPerfStats = lCombinedStatsTotal;

    double lSampleSize = mGameCharacteristics.getExactRolloutSampleSize();

    double lNewSampleSize = lSampleSize * 0.8 / lStatsDiff.mUsefulWorkFraction;

    // The measured ratio is really quite volatile.  To prevent the sample size jumping all over the place, adjust it
    // slowly.  The rules vary depending on whether the current sample size is very small (in which case we need to
    // take care to avoid rounding preventing any change).
    if (lSampleSize > 4)
    {
      // Only let the sample size 33% of the way towards its new value.  Also, only let it grow to 120% of its
      // previous value in one go.
      lNewSampleSize = (lNewSampleSize + (2 * lSampleSize)) / 3;
      lNewSampleSize = Math.min(1.2 * lSampleSize, lNewSampleSize);
    }
    else
    {
      // Very small sample size.  Jump straight to the new size, up to a maximum of 5.
      lNewSampleSize = Math.min(5.0,  lNewSampleSize);
    }

    // The sample size is always absolutely bound between 1 and 100 (inclusive).
    lNewSampleSize = Math.max(1.0,  Math.min(100.0, lNewSampleSize));

    // Set the new sample size, unless it has to be suppressed because turn end processing meant we couldn't fill the
    // pipeline (and therefore have invalid measurements from the rollout threads).
    if (!mSuppressSampleSizeUpdate)
    {
      mGameCharacteristics.setRolloutSampleSize(lNewSampleSize);
    }

    LOGGER.debug("Dynamic sample size");
    LOGGER.debug("  Useful work last time:  " + (int)(lStatsDiff.mUsefulWorkFraction * 100) + "%");
    LOGGER.debug("  Calculated sample size: " + (int)(lNewSampleSize + 0.5));
    LOGGER.debug("  Suppress update:        " + mSuppressSampleSizeUpdate);
    LOGGER.debug("  Now using sample size:  " + mGameCharacteristics.getRolloutSampleSize());
    LOGGER.debug("  Useful work total:      " + (int)(lCombinedStatsTotal.mUsefulWorkFraction * 100) + "%");

    mSuppressSampleSizeUpdate = false;
  }

  /**
   * Terminate the game searcher and all child threads.
   */
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

  /**
   * @return depth of root in game tree from initial state
   */
  public int getRootDepth()
  {
    return mRootDepth;
  }

  private void setRootDepth(int xiRootDepth)
  {
    mRootDepth = xiRootDepth;
  }
}