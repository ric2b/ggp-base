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
import org.w3c.tidy.MutableInteger;

public class MCTSTree
{
  private static final Logger LOGGER = LogManager.getLogger();

  class MoveScoreInfo
  {
    public double averageScore = 0;
    public int    numSamples = 0;
  }

  public static final boolean                          FREE_COMPLETED_NODE_CHILDREN                = true;                                                          //true;
  public static final boolean                          DISABLE_ONE_LEVEL_MINIMAX                   = true;
  private static final boolean                         SUPPORT_TRANSITIONS                         = true;
  public static final int                              MAX_SUPPORTED_BRANCHING_FACTOR              = 300;
  private static final int                             NUM_TOP_MOVE_CANDIDATES                     = 4;

  /**
   * For reasons not well understood, allowing select() to select complete children and propagate
   * upward their values (while playing out something that adds information at the level below) seems
   * harmful in most games, but strongly beneficial in some simultaneous move games.  Consequently
   * this flag allows it to be disabled for all but simultaneous move games
   */
  final boolean                                        allowAllGamesToSelectThroughComplete        = false;
  ForwardDeadReckonPropnetStateMachine                 underlyingStateMachine;
  volatile TreeNode                                    root = null;
  final int                                            numRoles;
  final CappedPool<TreeNode>                           nodePool;
  final ScoreVectorPool                                scoreVectorPool;
  final Pool<TreeEdge>                                 edgePool;
  final Pool<TreePath>                                 mPathPool;
  private final Map<ForwardDeadReckonInternalMachineState, TreeNode> mPositions;
  int                                                  sweepInstance                               = 0;
  List<TreeNode>                                       completedNodeQueue                          = new LinkedList<>();
  Map<Move, MoveScoreInfo>                             cousinMoveCache                             = new HashMap<>();
  long                                                 cousinMovesCachedFor                        = TreeNode.NULL_REF;
  final double[]                                       roleRationality;
  final double[]                                       bonusBuffer;
  long                                                 numCompletionsProcessed                     = 0;
  Random                                               r                                           = new Random();
  int                                                  numTerminalRollouts                         = 0;
  int                                                  numNonTerminalRollouts                      = 0;
  int                                                  numIncompleteNodes                          = 0;
  int                                                  numCompletedBranches                        = 0;
  int                                                  numNormalExpansions                         = 0;
  int                                                  numAutoExpansions                           = 0;
  int                                                  maxAutoExpansionDepth                       = 0;
  double                                               averageAutoExpansionDepth                   = 0;
  boolean                                              completeSelectionFromIncompleteParentWarned = false;
  int                                                  numReExpansions                             = 0;
  Heuristic                                            heuristic;
  final RoleOrdering                                   roleOrdering;
  final Role                                           mOurRole;
  RolloutProcessorPool                                 rolloutPool;
  RuntimeGameCharacteristics                           gameCharacteristics;
  Factor                                               factor;
  boolean                                              evaluateTerminalOnNodeCreation;
  private final TreeNodeAllocator                      mTreeNodeAllocator;
  final TreeEdgeAllocator                              mTreeEdgeAllocator;
  private final TreePathAllocator                      mTreePathAllocator;
  final GameSearcher                                   mGameSearcher;
  final StateSimilarityMap                             mStateSimilarityMap;
  private final ForwardDeadReckonInternalMachineState  mNonFactorInitialState;

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
    roleOrdering = xiRoleOrdering;
    mOurRole = xiRoleOrdering.roleIndexToRole(0);
    heuristic = xiHeuristic;
    gameCharacteristics = xiGameCharacateristics;
    rolloutPool = xiRolloutPool;
    mPositions = new HashMap<>((int)(nodePool.getCapacity() / 0.75f), 0.75f);

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
      }

      if (parent != null)
      {
        result.addParent(parent);

        //parent.adjustDescendantCounts(result.descendantCount+1);
      }

      //validateAll();
      return result;
    }
    finally
    {
      methodSection.exitScope();
    }
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
      }
    }
    //validateAll();

    if (root.complete && root.mNumChildren == 0)
    {
      LOGGER.info("Encountered complete root with trimmed children - must re-expand");
      root.complete = false;
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
    LOGGER.info("Num node re-expansions: " + numReExpansions);
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

    numReExpansions = 0;
    numNonTerminalRollouts = 0;
    numTerminalRollouts = 0;
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

      TreePath visited = mPathPool.allocate(mTreePathAllocator);
      TreeNode cur = root;
      TreePathElement selected = null;
      while (!cur.isUnexpanded())
      {
        selected = cur.select(visited, mJointMoveBuffer);
        cur = selected.getChildNode();
      }

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
      newNode.rollOut(visited, mGameSearcher.getPipeline(), forceSynchronous);
    }
    finally
    {
      methodSection.exitScope();
    }
  }
}
