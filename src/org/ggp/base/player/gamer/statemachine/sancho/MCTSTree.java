package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.HashMap;
import java.util.Map;
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
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.Heuristic.HeuristicInfo;
import org.ggp.base.player.gamer.statemachine.sancho.pool.CappedPool;
import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropositionInfo;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.Factor;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine.MoveWeights;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.StateMachineFilter;

public class MCTSTree
{
  private static final Logger LOGGER = LogManager.getLogger();

  public static final boolean                          FREE_COMPLETED_NODE_CHILDREN                = true;
  public static final boolean                          KEEP_BEST_COMPLETION_PATHS                  = true;
  public static final boolean                          DISABLE_ONE_LEVEL_MINIMAX                   = true;
  private static final boolean                         SUPPORT_TRANSITIONS                         = true;
  public static final int                              MAX_SUPPORTED_BRANCHING_FACTOR              = 300;
  public static final int                              MAX_SUPPORTED_TREE_DEPTH                    = 500;
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
   * Whether to use state-similarity measures to heuristically weight move selection
   */
  public static final boolean                          USE_STATE_SIMILARITY_IN_EXPANSION =
                         !MachineSpecificConfiguration.getCfgBool(CfgItem.DISABLE_STATE_SIMILARITY_EXPANSION_WEIGHTING);

  /**
   * Whether to use periodic normalization on node scors
   */
  public final boolean                                 USE_NODE_SCORE_NORMALIZATION = MachineSpecificConfiguration.getCfgBool(CfgItem.USE_NODE_SCORE_NORMALIZATION);
  /**
   * Whether to use UCB tuned as opposed to simple UCB
   */
  public final boolean                                 USE_UCB_TUNED;

  static final short  MAX_HYPER_RECURSION_DEPTH = 3;

  private long maxSelectTime = 0;
  private long maxExpandTime = 0;
  public int maxChildrenSeen = 0;

  final boolean                                        removeNonDecisionNodes;
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
  private final Map<ForwardDeadReckonInternalMachineState, Long> mPositions;
  int                                                  sweepInstance                               = 0;
  NodeRefQueue                                         completedNodeRefQueue                       = new NodeRefQueue(512);
  Map<Move, MoveScoreInfo>                             cousinMoveCache                             = new HashMap<>();
  long                                                 cousinMovesCachedFor                        = TreeNode.NULL_REF;
  final double[]                                       roleRationality;
  final double[]                                       bonusBuffer;
  final MoveWeights                                    weightsToUse;
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
  /**
   * The highest score seen in the current turn (for our role).
   */
  public int                              highestRolloutScoreSeen;

  /**
   * The lowest score seen in the current turn (for our role).
   */
  public int                              lowestRolloutScoreSeen;


  // Scratch variables for tree nodes to use to avoid unnecessary object allocation.
  // Note - several of these could probably be collapsed into a lesser number since they are not
  // concurrently used, but it's not worth the risk currently
  final HeuristicInfo                                 mNodeHeuristicInfo;
  final double[]                                      mNodeAverageScores;
  final double[]                                      mNodeAverageSquaredScores;
  final RolloutRequest                                mNodeSynchronousRequest;
  final ForwardDeadReckonLegalMoveInfo[]              mNodeTopMoveCandidates;
  final ForwardDeadReckonInternalMachineState         mStateScratchBuffer;
  final ForwardDeadReckonInternalMachineState[]       mChildStatesBuffer;
  final ForwardDeadReckonPropositionInfo[]            mRoleControlProps;
  final ForwardDeadReckonInternalMachineState         mNextStateBuffer;
  final ForwardDeadReckonLegalMoveInfo[]              mJointMoveBuffer;
  final ForwardDeadReckonLegalMoveInfo[]              mFastForwardPartialMoveBuffer;
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
                  RuntimeGameCharacteristics xiGameCharacteristics,
                  Heuristic xiHeuristic,
                  GameSearcher xiGameSearcher,
                  ForwardDeadReckonPropositionInfo[] roleControlProps)
  {
    underlyingStateMachine = xiStateMachine;
    numRoles = xiStateMachine.getRoles().length;
    mStateSimilarityMap = (MachineSpecificConfiguration.getCfgBool(CfgItem.DISABLE_STATE_SIMILARITY_EXPANSION_WEIGHTING) ? null : new StateSimilarityMap(xiStateMachine.getFullPropNet(), xiNodePool));
    mRoleControlProps = roleControlProps;
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

    //  Most of the time we don't want to create tree nodes representing stats in which
    //  only a single choice is available to whichever role is choosing at that node.
    //  However we continue to do so in two cases:
    //    1) Single player games - this is necessary to preserve plan-generation by (simple)
    //       backward walking of the tree from a win-terminal node
    //    2) Simultaneous move games, because we must assess the impact of a move in a cousin
    //       to cope with partial information, and a forced choice in one node does not imply
    //       a forced choice in all cousins
    removeNonDecisionNodes = (numRoles > 1 && xiGameCharacteristics.hasAdequateSampling && !xiGameCharacteristics.isSimultaneousMove && (!xiGameCharacteristics.isPseudoSimultaneousMove || xiFactor != null));

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
    if ( xiGameCharacteristics.numRoles > 1 &&
         xiGameCharacteristics.getAverageNonDrawLength() > 0 &&
         (xiGameCharacteristics.getGoalsStability() > GOALS_STABILITY_THRESHOLD ||
          (xiGameCharacteristics.getLongDrawsProportion() > 0.8 &&
           xiGameCharacteristics.getAverageNonDrawLength() <= xiGameCharacteristics.getAverageLength() &&
           xiGameCharacteristics.getAverageLength() < (xiGameCharacteristics.getMaxLength()+xiGameCharacteristics.getMinLength())*1.05/2.0)))
    {
      //  If goals are stable the decay depth is somewhat arbitrary, but we want finishes to be plausibly 'in range'
      //  so we use the shortest length seen from the initial state.
      //  If goals are NOT stable and we are using decay based on seeing
      //  non-draw results earlier than average finishes we use that non-draw average length
      if ( xiGameCharacteristics.getGoalsStability() > GOALS_STABILITY_THRESHOLD )
      {
        mWeightDecayKneeDepth = xiGameCharacteristics.getMinLength();
      }
      else
      {
        mWeightDecayKneeDepth = (int)xiGameCharacteristics.getAverageNonDrawLength();
      }
      //  Steepness of the cutoff is proportional th the depth of the knee (so basically we use
      //  a scale-free shape for decay) - this is an empirical decision and seems to be better than using
      //  std deviation of game length
      mWeightDecayScaleFactor = (double)mWeightDecayKneeDepth/6;
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

    if ( USE_NODE_SCORE_NORMALIZATION )
    {
      LOGGER.info("Using periodic node score normalization");
    }
    else
    {
      LOGGER.info("Not using periodic node score normalization");
    }

    USE_UCB_TUNED = MachineSpecificConfiguration.getCfgBool(CfgItem.USE_UCB_TUNED);
    if (USE_UCB_TUNED)
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
    gameCharacteristics = xiGameCharacteristics;
    rolloutPool = xiRolloutPool;
    mPositions = new HashMap<>((int)(nodePool.getCapacity() / 0.75f), 0.75f);

    //  For now we only automatically enable use of estimated values for unplayed nodes (in select)
    //  in games with negative goal latches, which amounts to ELB.  Further testing is needed, so for
    //  now wider enablement requires an explicit config setting.
    useEstimatedValueForUnplayedNodes = MachineSpecificConfiguration.getCfgBool(CfgItem.ENABLE_INITIAL_NODE_ESTIMATION) |
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
    weightsToUse = xiStateMachine.createMoveWeights();

    //  For now assume players in muli-player games are somewhat irrational.
    //  FUTURE - adjust during the game based on correlations with expected
    //  scores
    for (int i = 0; i < numRoles; i++)
    {
      if (xiGameCharacteristics.numRoles > 2)
      {
        roleRationality[i] = (i == 0 ? 1 : 0.8);
      }
      else
      {
        roleRationality[i] = 1;
      }
    }

    // Create the variables used by TreeNodes to avoid unnecessary object allocation.
    mNodeHeuristicInfo            = new HeuristicInfo(numRoles);
    mNodeAverageScores            = new double[numRoles];
    mNodeAverageSquaredScores     = new double[numRoles];
    mNodeSynchronousRequest       = new RolloutRequest(numRoles, underlyingStateMachine);
    mNodeTopMoveCandidates        = new ForwardDeadReckonLegalMoveInfo[NUM_TOP_MOVE_CANDIDATES];
    mCorrectedAverageScoresBuffer = new double[numRoles];
    mJointMoveBuffer              = new ForwardDeadReckonLegalMoveInfo[numRoles];
    mFastForwardPartialMoveBuffer = new ForwardDeadReckonLegalMoveInfo[numRoles];
    mBlendedCompletionScoreBuffer = new double[numRoles];
    mNextStateBuffer              = underlyingStateMachine.createEmptyInternalState();
    mChildStatesBuffer            = new ForwardDeadReckonInternalMachineState[MAX_SUPPORTED_BRANCHING_FACTOR];
    for (int lii = 0; lii < MAX_SUPPORTED_BRANCHING_FACTOR; lii++)
    {
      mChildStatesBuffer[lii] = underlyingStateMachine.createEmptyInternalState();
    }
    mStateScratchBuffer = underlyingStateMachine.createEmptyInternalState();
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
    TreeNode result = ((state != null && !disallowTransposition) ? findTransposition(state) : null);

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
        if (!disallowTransposition)
        {
          addToTranspositionIndexes(result);
        }
      }
      assert(!result.freed) : "Bad ref in positions table";
    }
    else
    {
      assert(!result.freed) : "Bad ref in positions table";

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

  /**
   * @return total number of logical node allocations made
   */
  public int getNumAllocations()
  {
    return numAllocations;
  }

  /**
   * @return total number of nodes allocations made that
   * were transpositions
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
    removeFromTranspositionIndexes(xiTreeNode);
  }

  public void addToTranspositionIndexes(TreeNode xiTreeNode)
  {
    if (SUPPORT_TRANSITIONS)
    {
      assert(!mPositions.containsKey(xiTreeNode.state));
      mPositions.put(xiTreeNode.state, xiTreeNode.getRef());
    }
  }

  public void removeFromTranspositionIndexes(TreeNode xiTreeNode)
  {
    if (SUPPORT_TRANSITIONS)
    {
      Long ref = mPositions.get(xiTreeNode.state);

      if ( ref == null )
      {
        return;
      }
      else if ( ref.longValue() == xiTreeNode.getRef() )
      {
        mPositions.remove(xiTreeNode.state);
      }
      else
      {
        //assert(false) : "Attempt to remove incorrect node from position indexes";
      }
    }
  }

  public TreeNode findTransposition(ForwardDeadReckonInternalMachineState xiState)
  {
    if ( SUPPORT_TRANSITIONS )
    {
      Long ref = mPositions.get(xiState);

      if ( ref == null )
      {
        return null;
      }

      TreeNode result = TreeNode.get(nodePool, ref);
      assert(result != null);
      assert(xiState.equals(result.state));
      assert(!removeNonDecisionNodes || result == root || result.complete || result.mNumChildren != 1);

      return result;
    }

    return null;
  }

  void processNodeCompletions()
  {
    while (!completedNodeRefQueue.isEmpty())
    {
      //validateAll();
      TreeNode lNode = TreeNode.get(nodePool, completedNodeRefQueue.remove());
      if (lNode != null)
      {
        assert(!lNode.freed) : "Freed node returned from pool";
        assert(lNode.complete) : "Queued node was not complete";
        lNode.processCompletion();
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
      //oldRoot = null;
      root = allocateNode(factorState, null, true);
      root.decidingRoleIndex = numRoles - 1;
      root.setDepth(rootDepth);
    }
    else
    {
      if ( root.state.equals(factorState))
      {
        assert ( rootDepth == 0 || factor != null );
        if ( rootDepth == 0 )
        {
          //  This is the start of the first turn, after some searching at the end of meta-gaming
          //  If the root score variance is 0 and this is a factored game, we mark this factor as
          //  uninteresting, and will henceforth spend no time searching it
          if ( factor != null &&
               root.numVisits > 500 &&
               lowestRolloutScoreSeen == highestRolloutScoreSeen )
          {
            mIsIrrelevantFactor = true;

            LOGGER.info("Identified irrelevant factor - supressing search");
          }
        }
      }
      else
      {
        TreeNode existingRootStateNode = findTransposition(factorState);

        //  In a factorized game there will be pseudo-noops, and the nodes generated by traversing the
        //  pseudo-noop from the root is not added to the transposition tables, and hence can result in the
        //  state we are searching for not being found, so we need to check that also
        if ( existingRootStateNode == null && factor != null )
        {
          existingRootStateNode = root.findNode(factorState, underlyingStateMachine.getRoles().length + 1);
        }

        assert(existingRootStateNode != null || root.findNode(factorState, underlyingStateMachine.getRoles().length + 1)==null);
        //assert(existingRootStateNode == null || existingRootStateNode.getDepth() > root.getDepth());

        //  In games with forced moves links will go from states-with-choices to other states-with-choices, so
        //  intermediate nodes (with no choices) will not exists.  This means that as the actual game path traverses
        //  such forced steps, and passes through such an intermediary state, it will not be found.  To hook up with
        //  the existing tree we need to 'fast-forward' from this node to the next (forced) state in order to find
        //  the point of linkage into the existing tree
        //  In the case where the state IS found, either it's our turn, in which case the existing choice node for this state
        //  can be used directly as the new root, or (and this can only happen if we are
        //  collapsing non-choice nodes) the existing node indexed for the root state is
        //  another role's choice.  In the latter case we need a 'proxy' node for the root
        //  so that it has the correct (singular) choice ready for getBestMove().  That
        //  singular choice will then point to the extant root state node, which is the
        //  actual decision node.
        TreeNode oldRoot = root;

        if ( existingRootStateNode != null && existingRootStateNode.decidingRoleIndex == numRoles-1 )
        {
          assert(oldRoot.linkageValid());

          assert(existingRootStateNode.linkageValid());
          root = existingRootStateNode;
          assert(root.parents.size()>0);
        }
        else
        {
          assert(existingRootStateNode == null || removeNonDecisionNodes);
          assert(existingRootStateNode == null || existingRootStateNode.linkageValid());

          //  Allocate proxy node for the root
          root = allocateNode(factorState, null, true);
          root.decidingRoleIndex = numRoles - 1;
          root.setDepth(rootDepth);

          //  Expand to get the correct (singular) choice
          root.expand(null, mJointMoveBuffer, rootDepth-1);

          assert(existingRootStateNode == null || root.mNumChildren == 1 || oldRoot.complete);

          //  In the forced-move case check to see if we can link into the existing search tree
          if ( root.mNumChildren == 1 )
          {
            TreeEdge selected;

            if (root.children[0] instanceof ForwardDeadReckonLegalMoveInfo)
            {
              //  Create an edge for the singular choice
              selected = edgePool.allocate(mTreeEdgeAllocator);
              selected.setParent(root, (ForwardDeadReckonLegalMoveInfo)root.children[0]);
              root.children[0] = selected;
            }
            else
            {
              selected = (TreeEdge)root.children[0];
            }

            mJointMoveBuffer[0] = selected.mPartialMove;

            if ( existingRootStateNode == null )
            {
              if (selected.getChildRef() == TreeNode.NULL_REF)
              {
                root.createChildNodeForEdge(selected, mJointMoveBuffer);
              }

              TreeNode createdForcedMoveChild = root.get(selected.getChildRef());
              if ( !createdForcedMoveChild.complete && createdForcedMoveChild.isUnexpanded())
              {
                TreePath visited = mPathPool.allocate(mTreePathAllocator);

                visited.push(root, selected);
                createdForcedMoveChild = createdForcedMoveChild.expand(visited, mJointMoveBuffer, rootDepth);
              }

              //  Have now created the first decision node and joined it into the existing tree
              //  if it existed there.
              existingRootStateNode = createdForcedMoveChild;
            }
            else
            {
              if ( selected.getChildRef() == TreeNode.NULL_REF )
              {
                selected.setChild(existingRootStateNode);
                existingRootStateNode.addParent(root);
              }
              else
              {
                assert(existingRootStateNode == root.get(selected.getChildRef()));
              }
            }

            assert(root.linkageValid());

            //  Set the root's count stats to those of the extant choice node it is effectively
            //  proxying
            selected.setNumVisits(existingRootStateNode.numVisits);
            root.numVisits = existingRootStateNode.numVisits;
            root.numUpdates = existingRootStateNode.numUpdates;
            for(int i = 0; i < numRoles; i++)
            {
              root.setAverageScore(i, existingRootStateNode.getAverageScore(i));
              root.setAverageSquaredScore(i, existingRootStateNode.getAverageSquaredScore(i));
            }

            if ( existingRootStateNode.complete)
            {
              //  There are two possible reasons the old root could have been marked as
              //  complete - either it was complete because of the state of its children
              //  in which case we want to re-propagate that, or else it had been marked
              //  complete by local search without known path (in which case we must re-find
              //  the completion and so clear the completion)
              if ( existingRootStateNode.isTerminal )
              {
                root.markComplete(existingRootStateNode, (short)existingRootStateNode.getDepth());
              }
              else
              {
                existingRootStateNode.complete = false;
                if ( !existingRootStateNode.isUnexpanded() )
                {
                  existingRootStateNode.checkChildCompletion(false);
                }
              }
            }
          }
        }

        oldRoot.freeAllBut(root);
        assert(existingRootStateNode == null || existingRootStateNode == root || existingRootStateNode.parents.size()==1);
        assert(root.parents.size() == 0);
      }
    }

    assert(!root.freed) : "Root node has been freed";
    assert(root.parents.size() == 0);
    //validateAll();

    //  Special case - because we can mark nodes complete before they are terminal if greedy rollouts are
    //  in use, it is possible for a complete root to not have a known child path as the root hits
    //  the penultimate move.  In such circumstances we must force re-expansion to identify the correct last
    //  move
    if ( root.complete && underlyingStateMachine.getIsGreedyRollouts() )
    {
      boolean foundChildPath = false;

      for(int i = 0; i < root.mNumChildren; i++ )
      {
        if ( root.children[i] instanceof TreeEdge &&
             ((TreeEdge)root.children[i]).getChildRef() != TreeNode.NULL_REF )
        {
          TreeNode child = root.get(((TreeEdge)root.children[i]).getChildRef());
          if ( child != null && child.complete && child.getAverageScore(0) == root.getAverageScore(0))
          {
            foundChildPath = true;
            break;
          }
        }
      }

      if ( !foundChildPath )
      {
        LOGGER.info("Complete root has no known child path - marking incomplete to force re-expansion");
        root.complete = false;
      }
    }
    if (root.mNumChildren == 0)
    {
      LOGGER.info("Encountered childless root - must re-expand");

      if ( root.complete )
      {
        numCompletedBranches--;
      }
      root.complete = false;
      //  Latched score detection can cause a node that is not strictly terminal (but has totally
      //  fixed scores for all subtrees) to be flagged as terminal - we must reset this to ensure
      //  it get re-expanded one level (from which we'll essentially make a random choice)
      root.isTerminal = false;

      //  Must expand here as async activity on the local search can mark the root complete again
      //  before an expansion takes place if we let control flow out of the synchronized section
      //  before expanding
      root.expand(null, mJointMoveBuffer, rootDepth-1);
    }

    LOGGER.info("Root has " + root.mNumChildren + " children, and is " + (root.complete ? "complete" : "not complete"));

    lowestRolloutScoreSeen = 1000;
    highestRolloutScoreSeen = -100;

    heuristic.newTurn(root.state, root);
  }

  /**
   * Perform a single MCTS expansion.
   *
   * @param forceSynchronous
   * @param xiChosenMove - the move which has already been chosen (for us) this turn.
   *
   * @return whether the tree is now fully explored.
   *
   * @throws MoveDefinitionException
   * @throws TransitionDefinitionException
   * @throws GoalDefinitionException
   * @throws InterruptedException
   */
  public boolean growTree(boolean forceSynchronous, Move xiChosenMove)
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
    selectAction(forceSynchronous, xiChosenMove);
    processNodeCompletions();
    return root.complete;
  }

  /**
   * @param firstDecision if false choice from the root always else choice from first node that has one
   * @return the best move from this tree.
   */
  public FactorMoveChoiceInfo getBestMove(boolean firstDecision)
  {
    FactorMoveChoiceInfo bestMoveInfo = root.getBestMove(true, null, firstDecision);

    if ( !firstDecision )
    {
      LOGGER.info("Num nodes in use: " + nodePool.getNumItemsInUse());
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
                  lowestRolloutScoreSeen + ", " +
                  highestRolloutScoreSeen + "]");

      numNonTerminalRollouts = 0;
      numTerminalRollouts = 0;
    }

    //root.dumpTree("treeDump.txt");
    return bestMoveInfo;
  }

  void validateAll()
  {
    if (root != null)
      root.validate(true);

    for (ForwardDeadReckonInternalMachineState e : mPositions.keySet())
    {
      TreeNode node = findTransposition(e);

      if ( node != null )
      {
        if (node.decidingRoleIndex != numRoles - 1)
        {
          LOGGER.warn("Position references bad type");
        }
        if (!node.state.equals(e))
        {
          LOGGER.warn("Position state mismatch");
        }
      }
    }

    for (Object lNodeAsObject : nodePool.getItemTable())
    {
      TreeNode lNode = (TreeNode)lNodeAsObject;
      if (lNode != null && !lNode.freed)
      {
        if (lNode.decidingRoleIndex == numRoles - 1)
        {
          if (lNode != findTransposition(lNode.state))
          {
            LOGGER.warn("Missing reference in positions table");
            LOGGER.warn("node state is: " + lNode.state + " with hash " + lNode.state.hashCode());
            LOGGER.warn(findTransposition(lNode.state));
          }
        }
      }
    }
  }

  /**
   * Set the forced moves for each role whose move IS forced in the specified state and we
   * are eliminating non-decision nodes
   * @param xiState
   * @param jointMove
   * @return true if all roles had forced moves
   */
  public boolean setForcedMoveProps(ForwardDeadReckonInternalMachineState xiState, ForwardDeadReckonLegalMoveInfo[] jointMove)
  {
    if ( removeNonDecisionNodes )
    {
      boolean result = true;

      for(int i = 0; i < numRoles; i++)
      {
        Role role = roleOrdering.roleIndexToRole(i);
        ForwardDeadReckonLegalMoveSet moves = underlyingStateMachine.getLegalMoveSet(xiState);
        if (searchFilter.getFilteredMovesSize(xiState, moves, role, true) == 1)
        {
          jointMove[i] = moves.getContents(role).iterator().next();
        }
        else
        {
          result = false;
        }
      }

      return result;
    }

    return false;
  }

  public boolean validateForcedMoveProps(ForwardDeadReckonInternalMachineState xiState, ForwardDeadReckonLegalMoveInfo[] jointMove)
  {
    if ( removeNonDecisionNodes )
    {
      for(int i = 0; i < numRoles; i++)
      {
        Role role = roleOrdering.roleIndexToRole(i);
        ForwardDeadReckonLegalMoveSet moves = underlyingStateMachine.getLegalMoveSet(xiState);
        if (searchFilter.getFilteredMovesSize(xiState, moves, role, true) == 1)
        {
          ForwardDeadReckonLegalMoveInfo expectedMove = moves.getContents(role).iterator().next();
          if ((jointMove[i].inputProposition == null) != (expectedMove.inputProposition == null))
          {
            return false;
          }
        }
      }
    }

    return true;
  }

  private void selectAction(boolean forceSynchronous, Move xiChosenMove)
    throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
    //completedNodeQueue.clear();

    long lSelectStartTime = System.nanoTime();
    TreePath visited = mPathPool.allocate(mTreePathAllocator);
    TreeNode cur = root;
    TreePathElement selected = null;
    int parentDepth = 0;

    while (!cur.isUnexpanded())
    {
      //  Hyper expand first choice layer for each role
      if ( cur.getDepth() < root.getDepth()+2*numRoles && cur.mNumChildren > 1 )
      {
        setForcedMoveProps(cur.state, mJointMoveBuffer);
        cur.hyperExpand(visited, mJointMoveBuffer, MAX_HYPER_RECURSION_DEPTH);
      }

      parentDepth = cur.getDepth();
      selected = cur.select(visited, mJointMoveBuffer, xiChosenMove);
      cur = selected.getChildNode();
      xiChosenMove = null;
    }

    long lExpandStartTime = System.nanoTime();
    TreeNode newNode;
    if (!cur.complete)
    {
      assert(selected == null || cur == selected.getChildNode());
      assert(selected == null || cur.parents.contains(selected.getParentNode()));

      //  Expand for each role so we're back to our-move as we always rollout after joint moves
      cur = cur.expand(visited, mJointMoveBuffer, parentDepth);
      assert(selected == null || cur == selected.getChildNode());
    }


    //  Even if we've selected a terminal node we still do a pseudo-rollout
    //  from it so its value gets a weight increase via back propagation
    newNode = cur;

    long selectTime = lExpandStartTime - lSelectStartTime;
    long expandTime = System.nanoTime() - lExpandStartTime;

    if ( selectTime > maxSelectTime )
    {
      maxSelectTime = selectTime;
      LOGGER.debug("Max select time seen (ms): " + (selectTime/1000000));
    }
    if ( expandTime > maxExpandTime )
    {
      maxExpandTime = expandTime;
      LOGGER.debug("Max expand time seen (ms): " + (expandTime/1000000));
    }

    // Perform the rollout request.
    assert(selected != null || newNode == root);
    assert(selected == null || newNode == selected.getChildNode());
    assert(!newNode.freed);
    newNode.rollOut(visited,
                    mGameSearcher.getPipeline(),
                    forceSynchronous,
                    selectTime,
                    expandTime);
  }
}
