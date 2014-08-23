package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.MachineSpecificConfiguration.CfgItem;
import org.ggp.base.player.gamer.statemachine.sancho.MoveScoreInfo.MoveScoreInfoAllocator;
import org.ggp.base.player.gamer.statemachine.sancho.TreeEdge.TreeEdgeAllocator;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode.TreeNodeAllocator;
import org.ggp.base.player.gamer.statemachine.sancho.TreePath.TreePathAllocator;
import org.ggp.base.player.gamer.statemachine.sancho.TreePath.TreePathElement;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.Heuristic;
import org.ggp.base.player.gamer.statemachine.sancho.pool.CappedPool;
import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.Factor;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.StateMachineFilter;
import org.w3c.tidy.MutableInteger;

public class MCTSTree
{
  private static final Logger LOGGER = LogManager.getLogger();

  public static final boolean                          FREE_COMPLETED_NODE_CHILDREN                = true;                                                          //true;
  public static final boolean                          DISABLE_ONE_LEVEL_MINIMAX                   = true;
  private static final boolean                         SUPPORT_TRANSITIONS                         = true;
  public static final int                              MAX_SUPPORTED_BRANCHING_FACTOR              = 300;
  private static final int                             NUM_TOP_MOVE_CANDIDATES                     = 4;
  /**
   * If goal stabiliy is above a certain threshold we can use interim-state goals to predict final results
   * which makes the use of weight decay and cutoffs appropriate
   */
  private static final double                          GOALS_STABILITY_THRESHOLD                   = 0.65;
  /**
   * The point in the weight decay at which cutoff occurs is set by how many sigmas past the knee
   * (4 is a weight of approximately 0.018, with the knee being the point of symmetry at 0.5)
   */
  private static final double                          CUTOFF_SIGMA                                 = 4;

  /**
   * Whether to use state-similarity measures to heuristically weight mov selection
   */
  public final boolean                                 USE_STATE_SIMILARITY_IN_EXPANSION = !MachineSpecificConfiguration.getCfgVal(CfgItem.DISABLE_STATE_SIMILARITY_EXPANSION_WEIGHTING, false);
  /**
   * Whether to use UCB tuned as opposed to simple UCB
   */
  public final boolean                                 USE_UCB_TUNED;

  /**
   * For reasons not well understood, allowing select() to select complete children and propagate
   * upward their values (while playing out something that adds information at the level below) seems
   * harmful in most games, but strongly beneficial in some simultaneous move games.  Consequently
   * this flag allows it to be disabled for all but simultaneous move games
   *
   * UPDATE - a recent modification to prevent the mechanism distorting the perceived variance
   * of ancestor nodes when this mechanism is triggered appears to have resolved the observed degradation
   * previously seen in C4, Pentago, and a few other games.  Results with it enabled are now no worse
   * in any game so far tested, and significantly better in some, so default enabling at this time.
   * Eventually this flag will probably be removed once we're more confident that always enabling is ok
   */
  final boolean                                        allowAllGamesToSelectThroughComplete        = true;
  final boolean                                        useEstimatedValueForUnplayedNodes;
  ForwardDeadReckonPropnetStateMachine                 underlyingStateMachine;
  volatile TreeNode                                    root = null;
  final int                                            numRoles;
  final int                                            mWeightDecayKneeDepth;
  final double                                         mWeightDecayScaleFactor;
  final int                                            mWeightDecayCutoffDepth;
  final CappedPool<TreeNode>                           nodePool;
  final ScoreVectorPool                                scoreVectorPool;
  final Pool<TreeEdge>                                 edgePool;
  final Pool<TreePath>                                 mPathPool;
  final CappedPool<MoveScoreInfo>                      mCachedMoveScorePool;
  private final Map<ForwardDeadReckonInternalMachineState, TreeNode> mPositions;
  int                                                  sweepInstance                               = 0;
  List<TreeNode>                                       completedNodeQueue                          = new LinkedList<>();
  Map<Move, MoveScoreInfo>                             cousinMoveCache                             = new HashMap<>();
  long                                                 cousinMovesCachedFor                        = TreeNode.NULL_REF;
  final double[]                                       roleRationality;
  final double[]                                       bonusBuffer;
  final int[]                                          latchedScoreRangeBuffer                     = new int[2];
  final int[]                                          roleMaxScoresBuffer;
  long                                                 numCompletionsProcessed                     = 0;
  Random                                               r                                           = new Random();
  int                                                  numTerminalRollouts                         = 0;
  int                                                  numNonTerminalRollouts                      = 0;
  int                                                  numIncompleteNodes                          = 0;
  int                                                  numCompletedBranches                        = 0;
  int                                                  numNormalExpansions                         = 0;
  int                                                  numAutoExpansions                           = 0;
  int                                                  maxAutoExpansionDepth                       = 0;
  int                                                  numAllocations                              = 0;
  int                                                  numTranspositions                           = 0;
  double                                               averageAutoExpansionDepth                   = 0;
  boolean                                              completeSelectionFromIncompleteParentWarned = false;
  int                                                  numReExpansions                             = 0;
  Heuristic                                            heuristic;
  final RoleOrdering                                   roleOrdering;
  final Role                                           mOurRole;
  RolloutProcessorPool                                 rolloutPool;
  RuntimeGameCharacteristics                           gameCharacteristics;
  final Factor                                         factor;
  final StateMachineFilter                             searchFilter;
  boolean                                              evaluateTerminalOnNodeCreation;
  private final TreeNodeAllocator                      mTreeNodeAllocator;
  final TreeEdgeAllocator                              mTreeEdgeAllocator;
  final MoveScoreInfoAllocator                         mMoveScoreInfoAllocator;
  private final TreePathAllocator                      mTreePathAllocator;
  final GameSearcher                                   mGameSearcher;
  final StateSimilarityMap                             mStateSimilarityMap;
  private final ForwardDeadReckonInternalMachineState  mNonFactorInitialState;
  public boolean                                       mIsIrrelevantFactor = false;

  // Scratch variables for tree nodes to use to avoid unnecessary object allocation.
  // Note - several of these could probably be collapsed into a lesser number since they are not
  // concurrently used, but it's not worth the risk currently
  final double[]                                      mNodeHeuristicValues;
  final MutableInteger                                mNodeHeuristicWeight;
  final double[]                                      mNodeAverageScores;
  final double[]                                      mNodeAverageSquaredScores;
  final RolloutRequest                                mNodeSynchronousRequest;
  final ForwardDeadReckonLegalMoveInfo[]              mNodeTopMoveCandidates;
  final ForwardDeadReckonInternalMachineState[]       mChildStatesBuffer;
  final ForwardDeadReckonInternalMachineState         mNextStateBuffer;
  final ForwardDeadReckonLegalMoveInfo[]              mJointMoveBuffer;
  final double[]                                      mCorrectedAverageScoresBuffer;
  final double[]                                      mBlendedCompletionScoreBuffer;

  public MCTSTree(ForwardDeadReckonPropnetStateMachine xiStateMachine,
                  Factor xiFactor,
                  CappedPool<TreeNode> xiNodePool,
                  ScoreVectorPool xiScorePool,
                  Pool<TreeEdge> xiEdgePool,
                  Pool<TreePath> xiPathPool,
                  RoleOrdering xiRoleOrdering,
                  RolloutProcessorPool xiRolloutPool,
                  RuntimeGameCharacteristics xiGameCharacateristics,
                  Heuristic xiHeuristic,
                  GameSearcher xiGameSearcher)
  {
    underlyingStateMachine = xiStateMachine;
    numRoles = xiStateMachine.getRoles().length;
    mStateSimilarityMap = (MachineSpecificConfiguration.getCfgVal(CfgItem.DISABLE_STATE_SIMILARITY_EXPANSION_WEIGHTING, false) ? null : new StateSimilarityMap(xiStateMachine.getFullPropNet(), xiNodePool));
    nodePool = xiNodePool;
    scoreVectorPool = xiScorePool;
    edgePool = xiEdgePool;
    mPathPool = xiPathPool;
    factor = xiFactor;
    if ( factor != null )
    {
      searchFilter = factor;
    }
    else
    {
      searchFilter = xiStateMachine.getBaseFilter();
    }

    //  Apply decay and cutoff if either:
    //    1)  The goals are sufficiently stable (goals in a non-terminal state are a good predictor
    //        of final result)
    //    2)  All of the following apply:
    //        2.1) Non-draws cluster at shallower depths on average than do draws
    //        2.2) Mean game length is not much past median game length (which would imply results tend to happen late)
    //        2.3) Max-length games are almost all draws
    //        These conditions are a proxy for a more general (but harder) analysis of the distribution of results
    //        wherein win vs draw distribution over depth tends not to increase.  Mostly it is intended to capture
    //        the use of decay in games that have artificial draw-after-N-turns terminal conditions and where the non-draws
    //        can happen a lot earlier
    if ( xiGameCharacateristics.getAverageNonDrawLength() > 0 &&
         (xiGameCharacateristics.getGoalsStability() > GOALS_STABILITY_THRESHOLD ||
          (xiGameCharacateristics.getMaxGameLengthDrawsProportion() > 0.9 &&
           xiGameCharacateristics.getAverageNonDrawLength() <= xiGameCharacateristics.getAverageLength() &&
           xiGameCharacateristics.getAverageLength() < (xiGameCharacateristics.getMaxLength()+xiGameCharacateristics.getMinLength())*1.05/2.0)))
    {
      //  If goals are stable the decay depth is somewhat arbitrary, but we want finishes to be plausibly 'in range'
      //  so we use the shortest length seen from the initial state.
      //  If goals are NOT stable and we are using decay based on seeing
      //  non-draw results earlier than average finishes we use that non-draw average length
      if ( xiGameCharacateristics.getGoalsStability() > GOALS_STABILITY_THRESHOLD )
      {
        mWeightDecayKneeDepth = xiGameCharacateristics.getMinLength();
      }
      else
      {
        mWeightDecayKneeDepth = (int)xiGameCharacateristics.getAverageNonDrawLength();
      }
      //  Steepness of the cutoff is proportional th the depth of the knee (so basically we use
      //  a scale-free shape for decay) - this is an empirical decision and seems to be better than using
      //  std deviation of game length
      mWeightDecayScaleFactor = mWeightDecayKneeDepth/6;
      //  Cutoff set to occur at fixed decay factor
      mWeightDecayCutoffDepth = mWeightDecayKneeDepth + (int)(mWeightDecayScaleFactor*CUTOFF_SIGMA);
      LOGGER.info("Weight decay knee and scale factor: (" + mWeightDecayKneeDepth + ", " + mWeightDecayScaleFactor + ")");
    }
    else
    {

      mWeightDecayKneeDepth = -1;
      mWeightDecayScaleFactor = 0;
      mWeightDecayCutoffDepth = 1000;

      LOGGER.info("Weight decay disabled");
    }

    USE_UCB_TUNED = MachineSpecificConfiguration.getCfgVal(CfgItem.USE_UCB_TUNED, true);
    if ( USE_UCB_TUNED )
    {
      LOGGER.info("Using UCB-tuned");
    }
    else
    {
      LOGGER.info("Using simple UCB");
    }

    if ( mWeightDecayCutoffDepth >= 1000 )
    {
      LOGGER.info("Early cutoff disabled");
    }
    else
    {
      LOGGER.info("Early cutoff depth: " + mWeightDecayCutoffDepth);
    }

    roleOrdering = xiRoleOrdering;
    mOurRole = xiRoleOrdering.roleIndexToRole(0);
    heuristic = xiHeuristic;
    gameCharacteristics = xiGameCharacateristics;
    rolloutPool = xiRolloutPool;
    mPositions = new HashMap<>((int)(nodePool.getCapacity() / 0.75f), 0.75f);

    //  For now we only automatically enable use of estimated values for unplayed nodes (in select)
    //  in games with negative goal latches, which amounts to ELB.  Further testing is needed, so for
    //  now wider enablement requires an explicit config setting.
    useEstimatedValueForUnplayedNodes = MachineSpecificConfiguration.getCfgVal(CfgItem.ENABLE_INITIAL_NODE_ESTIMATION, false) |
                                        underlyingStateMachine.hasNegativelyLatchedGoals();

    if ( useEstimatedValueForUnplayedNodes )
    {
      LOGGER.info("Estimated initial values for nodes with no play-throughs is enabled");
    }
    if ( xiFactor != null )
    {
      mNonFactorInitialState = xiStateMachine.createInternalState(xiStateMachine.getInitialState());
      mNonFactorInitialState.intersect(xiFactor.getInverseStateMask(false));
    }
    else
    {
      mNonFactorInitialState = null;
    }

    evaluateTerminalOnNodeCreation = !gameCharacteristics.getIsFixedMoveCount();

    numCompletionsProcessed = 0;
    completeSelectionFromIncompleteParentWarned = false;
    mTreeNodeAllocator = new TreeNodeAllocator(this);
    mTreeEdgeAllocator = new TreeEdgeAllocator();
    mTreePathAllocator = new TreePathAllocator(this);
    mGameSearcher = xiGameSearcher;

    bonusBuffer = new double[numRoles];
    roleRationality = new double[numRoles];
    roleMaxScoresBuffer = new int[numRoles];

    //  For now assume players in muli-player games are somewhat irrational.
    //  FUTURE - adjust during the game based on correlations with expected
    //  scores
    for (int i = 0; i < numRoles; i++)
    {
      if (xiGameCharacateristics.numRoles > 2)
      {
        roleRationality[i] = (i == 0 ? 1 : 0.8);
      }
      else
      {
        roleRationality[i] = 1;
      }
    }

    // Create the variables used by TreeNodes to avoid unnecessary object allocation.
    mNodeHeuristicValues          = new double[numRoles];
    mNodeHeuristicWeight          = new MutableInteger();
    mNodeAverageScores            = new double[numRoles];
    mNodeAverageSquaredScores     = new double[numRoles];
    mNodeSynchronousRequest       = new RolloutRequest(numRoles, underlyingStateMachine);
    mNodeTopMoveCandidates        = new ForwardDeadReckonLegalMoveInfo[NUM_TOP_MOVE_CANDIDATES];
    mCorrectedAverageScoresBuffer = new double[numRoles];
    mJointMoveBuffer              = new ForwardDeadReckonLegalMoveInfo[numRoles];
    mBlendedCompletionScoreBuffer = new double[numRoles];
    mNextStateBuffer              = new ForwardDeadReckonInternalMachineState(underlyingStateMachine.getInfoSet());
    mChildStatesBuffer            = new ForwardDeadReckonInternalMachineState[MAX_SUPPORTED_BRANCHING_FACTOR];
    for (int lii = 0; lii < MAX_SUPPORTED_BRANCHING_FACTOR; lii++)
    {
      mChildStatesBuffer[lii] = new ForwardDeadReckonInternalMachineState(underlyingStateMachine.getInfoSet());
    }
    mMoveScoreInfoAllocator = new MoveScoreInfoAllocator(numRoles);
    mCachedMoveScorePool = new CappedPool<>(MAX_SUPPORTED_BRANCHING_FACTOR);
  }

  public void empty()
  {
    numCompletedBranches = 0;
    numNormalExpansions = 0;
    numAutoExpansions = 0;
    maxAutoExpansionDepth = 0;
    averageAutoExpansionDepth = 0;
    root = null;
    nodePool.clear(mTreeNodeAllocator, true);
    mPositions.clear();
    numIncompleteNodes = 0;
  }

  TreeNode allocateNode(ForwardDeadReckonInternalMachineState state,
                        TreeNode parent,
                        boolean disallowTransposition)
  {
    ProfileSection methodSection = ProfileSection.newInstance("allocateNode");
    try
    {
      TreeNode result = ((state != null && SUPPORT_TRANSITIONS && !disallowTransposition) ? mPositions.get(state) : null);

      //validateAll();
      //  Use of pseudo-noops in factors can result in recreation of the root state (only)
      //  a lower level with a joint move of (pseudo-noop, noop, noop, ..., noop).  This
      //  must not be linked back to or else a loop will be created
      if ((!SUPPORT_TRANSITIONS || result == null) || result == root)
      {
        //LOGGER.debug("Add state " + state);
        result = nodePool.allocate(mTreeNodeAllocator);

        //if ( positions.values().contains(result))
        //{
        //  LOGGER.info("Node already referenced by a state!");
        //}
        if (state != null)
        {
          result.setState(state);
          if (SUPPORT_TRANSITIONS && !disallowTransposition)
          {
            mPositions.put(result.state, result);
          }
        }
        assert(!result.freed) : "Bad ref in positions table";
      }
      else
      {
        assert(!result.freed) : "Bad ref in positions table";
        assert(result.decidingRoleIndex == numRoles - 1) : "Non-null move in position cache";

        numTranspositions++;
      }

      if (parent != null)
      {
        result.addParent(parent);

        //parent.adjustDescendantCounts(result.descendantCount+1);
      }

      numAllocations++;

      //validateAll();
      return result;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  /**
   * @return total number of logical node allocations made
   */
  public int getNumAllocations()
  {
    return numAllocations;
  }

  /**
   * @return total number of nodes allocations made that were transpositions
   */
  public int getNumTranspositions()
  {
    return numTranspositions;
  }

  /**
   * Inform the tree that a node is being freed.
   *
   * @param xiTreeNode - the node that is being freed.
   */
  public void nodeFreed(TreeNode xiTreeNode)
  {
    if (SUPPORT_TRANSITIONS)
    {
      mPositions.remove(xiTreeNode.state);
    }
  }

  void processNodeCompletions()
  {
    while (!completedNodeQueue.isEmpty())
    {
      //validateAll();
      TreeNode node = completedNodeQueue.remove(0);

      if (!node.freed)
      {
        node.processCompletion();
      }
    }
  }

  void makeFactorState(ForwardDeadReckonInternalMachineState state)
  {
    state.intersect(factor.getStateMask(false));
    //  Set the rest of the state to 'neutral' values.  We use the initial state
    //  as this is guaranteed to be legal and non-terminal
    state.merge(mNonFactorInitialState);
  }

  public void setRootState(ForwardDeadReckonInternalMachineState state, short rootDepth)
  {
    ForwardDeadReckonInternalMachineState factorState;

    if ( factor == null )
    {
      factorState = state;
    }
    else
    {
      factorState = new ForwardDeadReckonInternalMachineState(state);
      makeFactorState(factorState);
    }

    if (root == null)
    {
      root = allocateNode(factorState, null, false);
      root.decidingRoleIndex = numRoles - 1;
      root.setDepth(rootDepth);
    }
    else
    {
      TreeNode newRoot = root.findNode(factorState, underlyingStateMachine.getRoles().length + 1);
      if (newRoot == null)
      {
        if (root.complete)
        {
          LOGGER.info("New root missing because old root was complete");
        }
        else
        {
          LOGGER.warn("Unable to find root node in existing tree");
        }
        empty();
        root = allocateNode(factorState, null, false);
        root.decidingRoleIndex = numRoles - 1;
        root.setDepth(rootDepth);
      }
      else
      {
        //  Note - we cannot assert that the root depth matches what we're given.  This
        //  is because in some games the same state can occur at different depths (English Draughts
        //  exhibits this), which means that transitions to the same node can occur at multiple
        //  depths.  The result is that the 'depth' of a node can only be considered indicative
        //  rather than absolute.  This is good enough for our current usage, but should be borne
        //  in mind if that usage is expanded.
        if (newRoot != root)
        {
          root.freeAllBut(newRoot);
          assert(!newRoot.freed) : "Root node has been freed";
          root = newRoot;
        }
        else
        {
          if ( rootDepth == 0 )
          {
            //  This is the start of the first turn, after some searching at the end of meta-gaming
            //  If the root score variance is 0 and this is a factored game, we mark this factor as
            //  uninteresting, and will henceforth spend no time searching it
            if ( factor != null &&
                 root.numVisits > 500 &&
                 Math.abs(root.getAverageSquaredScore(0) - root.getAverageScore(0)*root.getAverageScore(0)) < TreeNode.EPSILON )
            {
              mIsIrrelevantFactor = true;

              LOGGER.info("Identified irrelevant factor - supressing search");
            }
          }
        }
      }
    }
    //validateAll();

    if (root.complete && root.mNumChildren == 0)
    {
      LOGGER.info("Encountered complete root with trimmed children - must re-expand");
      root.complete = false;
      //  Latched score detection can cause a node that is not strictly terminal (but has totally
      //  fixed scores for all subtrees) to be flagged as terminal - we must reset this to ensure
      //  it get re-expanded one level (from which we'll essentially make a random choice)
      root.isTerminal = false;
      numCompletedBranches--;
    }

    heuristic.newTurn(root.state, root);
  }

  /**
   * Perform a single MCTS expansion.
   * @param forceSynchronous
   *
   * @return whether the tree is now fully explored.
   *
   * @throws MoveDefinitionException
   * @throws TransitionDefinitionException
   * @throws GoalDefinitionException
   * @throws InterruptedException
   */
  public boolean growTree(boolean forceSynchronous)
    throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
    //  In an irrelevant factor we don't want to waste time searching - just need
    //  to do enough for the children to be enumerable
    if ( mIsIrrelevantFactor && !root.hasUnexpandedChoices() )
    {
      return false;
    }

    //validateAll();
    //validationCount++;
    selectAction(forceSynchronous);
    processNodeCompletions();
    return root.complete;
  }

  /**
   * @return the best top-level move from this tree.
   */
  public FactorMoveChoiceInfo getBestMove()
  {
    FactorMoveChoiceInfo bestMoveInfo = root.getBestMove(true, null);

    LOGGER.info("Num true rollouts added: " + numNonTerminalRollouts);
    LOGGER.info("Num terminal nodes revisited: " + numTerminalRollouts);
    LOGGER.info("Num incomplete nodes: " + numIncompleteNodes);
    LOGGER.info("Num completely explored branches: " + numCompletedBranches);
    if (numAutoExpansions + numNormalExpansions > 0)
    {
      LOGGER.info("Percentage forced single-choice expansion: " +
                  ((double)numAutoExpansions / (numAutoExpansions + numNormalExpansions)));
      LOGGER.info("Average depth of auto-expansion instances: " + averageAutoExpansionDepth);
      LOGGER.info("Maximum depth of auto-expansion instances: " + maxAutoExpansionDepth);
    }
    LOGGER.info("Current observed rollout score range: [" +
                mGameSearcher.lowestRolloutScoreSeen + ", " +
                mGameSearcher.highestRolloutScoreSeen + "]");

    numNonTerminalRollouts = 0;
    numTerminalRollouts = 0;

    //root.dumpTree("c:\\temp\\mctsTree.txt");
    return bestMoveInfo;
  }

  void validateAll()
  {
    if (root != null)
      root.validate(true);

    for (Entry<ForwardDeadReckonInternalMachineState, TreeNode> e : mPositions.entrySet())
    {
      if (e.getValue().decidingRoleIndex != numRoles - 1)
      {
        LOGGER.warn("Position references bad type");
      }
      if (!e.getValue().state.equals(e.getKey()))
      {
        LOGGER.warn("Position state mismatch");
      }
    }

    for (TreeNode node : nodePool.getItemTable())
    {
      if (node != null && !node.freed)
      {
        if (node.decidingRoleIndex == numRoles - 1)
        {
          if (node != mPositions.get(node.state))
          {
            LOGGER.warn("Missing reference in positions table");
            LOGGER.warn("node state is: " + node.state + " with hash " + node.state.hashCode());
            LOGGER.warn(mPositions.get(node.state));
          }
        }
      }
    }
  }

  private void selectAction(boolean forceSynchronous) throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
    ProfileSection methodSection = ProfileSection.newInstance("TreeNode.selectAction");
    try
    {
      //validateAll();
      completedNodeQueue.clear();

      long lSelectStartTime = System.nanoTime();
      TreePath visited = mPathPool.allocate(mTreePathAllocator);
      TreeNode cur = root;
      TreePathElement selected = null;
      while (!cur.isUnexpanded())
      {
        selected = cur.select(visited, mJointMoveBuffer);
        cur = selected.getChildNode();
      }

      long lExpandStartTime = System.nanoTime();
      TreeNode newNode;
      if (!cur.complete)
      {
        //  Expand for each role so we're back to our-move as we always rollout after joint moves
        cur.expand(selected, mJointMoveBuffer);

        if (!cur.complete)
        {
          selected = cur.select(visited, mJointMoveBuffer);
          newNode = selected.getChildNode();

          int autoExpansionDepth = 0;

          while ((newNode.decidingRoleIndex != numRoles - 1 || newNode.autoExpand) &&
                 !newNode.complete)
          {
            if ( newNode.decidingRoleIndex == numRoles - 1 )
            {
              autoExpansionDepth++;
            }
            //  Might have transposed into an already expanded node, in which case no need
            //  to do another
            if (newNode.isUnexpanded())
            {
              newNode.expand(selected, mJointMoveBuffer);
            }
            if (!newNode.complete)
            {
              selected = newNode.select(visited, mJointMoveBuffer);
              newNode = selected.getChildNode();
            }
          }

          if ( autoExpansionDepth > 0 )
          {
            averageAutoExpansionDepth = (averageAutoExpansionDepth*numAutoExpansions + autoExpansionDepth)/(numAutoExpansions+1);
            numAutoExpansions++;
            if ( autoExpansionDepth > maxAutoExpansionDepth )
            {
              maxAutoExpansionDepth = autoExpansionDepth;
            }
          }
          else
          {
            numNormalExpansions++;
          }
        }
        else
        {
          newNode = cur;
        }
      }
      else
      {
        //  If we've selected a terminal node we still do a pseudo-rollout
        //  from it so its value gets a weight increase via back propagation
        newNode = cur;
      }

      // Perform the rollout request.
      assert(!newNode.freed);
      newNode.rollOut(visited,
                      mGameSearcher.getPipeline(),
                      forceSynchronous,
                      lExpandStartTime - lSelectStartTime,
                      System.nanoTime() - lExpandStartTime);
    }
    finally
    {
      methodSection.exitScope();
    }
  }
}
