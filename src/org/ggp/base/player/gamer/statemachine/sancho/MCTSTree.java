package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode.TreeNodeAllocator;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode.TreeNodeRef;
import org.ggp.base.player.gamer.statemachine.sancho.TreePath.TreePathElement;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.Heuristic;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.Factor;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class MCTSTree
{
  private static final Logger LOGGER = LogManager.getLogger();

  class LRUNodeMoveWeightsCache extends LinkedHashMap<TreeNode, MoveWeightsCollection>
  {
    /**
     *
     */
    private static final long serialVersionUID = 1L;
    private int               maxEntries;

    public LRUNodeMoveWeightsCache(int capacity)
    {
      super(capacity + 1, 1.0f, true);
      maxEntries = capacity;
    }

    @Override
    protected boolean removeEldestEntry(final Map.Entry<TreeNode, MoveWeightsCollection> eldest)
    {
      return super.size() > maxEntries;
    }
  }

  class MoveScoreInfo
  {
    public double averageScore = 0;
    public double sampleWeight = 0;
  }


  final boolean                                        freeCompletedNodeChildren                   = true;                                                          //true;
  final boolean                                        disableOnelevelMinimax                      = true;
  /**
   * For reasons not well understood, allowing select() to select complete children and propagate
   * upward their values (while playing out something that adds information at the level below) seems
   * harmful in most games, but strongly beneficial in some simultaneous move games.  Consequently
   * this flag allows it to be disabled for all but simultaneous move games
   */
  final boolean                                        allowAllGamesToSelectThroughComplete        = false;
  ForwardDeadReckonPropnetStateMachine                 underlyingStateMachine;
  volatile TreeNode                                    root = null;
  int                                                  numRoles;
  LRUNodeMoveWeightsCache                              nodeMoveWeightsCache                        = null;
  CappedPool<TreeNode>                                 nodePool;
  Map<ForwardDeadReckonInternalMachineState, TreeNode> positions                                   = new HashMap<>();
  int                                                  sweepInstance                               = 0;
  List<TreeNode>                                       completedNodeQueue                          = new LinkedList<>();
  Map<Move, MoveScoreInfo>                             cousinMoveCache                             = new HashMap<>();
  TreeNodeRef                                          cousinMovesCachedFor                        = null;
  double[]                                             bonusBuffer                                 = null;
  double[]                                             roleRationality                             = null;
  long                                                 numCompletionsProcessed                     = 0;
  Random                                               r                                           = new Random();
  int                                                  numUniqueTreeNodes                          = 0;
  int                                                  numTotalTreeNodes                           = 0;
  int                                                  numTerminalRollouts                         = 0;
  int                                                  numNonTerminalRollouts                      = 0;
  int                                                  numIncompleteNodes                          = 0;
  int                                                  numCompletedBranches                        = 0;
  int                                                  numNormalExpansions                         = 0;
  int                                                  numAutoExpansions                           = 0;
  int                                                  maxAutoExpansionDepth                       = 0;
  double                                               averageAutoExpansionDepth                   = 0;
  boolean                                              completeSelectionFromIncompleteParentWarned = false;
  int                                                  numSelectionsThroughIncompleteNodes         = 0;
  int                                                  numReExpansions                             = 0;
  Heuristic                                            heuristic;
  final RoleOrdering                                   roleOrdering;
  final Role                                           mOurRole;
  RolloutProcessorPool                                 rolloutPool;
  RuntimeGameCharacteristics                           gameCharacteristics;
  Factor                                               factor;
  boolean                                              evaluateTerminalOnNodeCreation;
  private final TreeNodeAllocator                      mTreeNodeAllocator;
  final GameSearcher                                   mGameSearcher;

  // Scratch variables for tree nodes to use to avoid unnecessary object allocation.
  final double[] mNodeAverageScores;
  final double[] mNodeAverageSquaredScores;
  final RolloutRequest mNodeSynchronousRequest;

  public MCTSTree(ForwardDeadReckonPropnetStateMachine stateMachine,
                  Factor factor,
                  CappedPool nodePool,
                  RoleOrdering roleOrdering,
                  RolloutProcessorPool rolloutPool,
                  RuntimeGameCharacteristics gameCharacateristics,
                  Heuristic heuristic,
                  GameSearcher xiGameSearcher)
  {
    underlyingStateMachine = stateMachine;
    numRoles = stateMachine.getRoles().size();
    this.nodePool = nodePool;
    this.factor = factor;
    this.roleOrdering = roleOrdering;
    this.mOurRole = roleOrdering.roleIndexToRole(0);
    this.heuristic = heuristic;
    this.gameCharacteristics = gameCharacateristics;
    this.rolloutPool = rolloutPool;

    evaluateTerminalOnNodeCreation = !gameCharacteristics.getIsFixedMoveCount();

    nodeMoveWeightsCache = new LRUNodeMoveWeightsCache(5000);

    bonusBuffer = new double[numRoles];
    roleRationality = new double[numRoles];
    numCompletionsProcessed = 0;
    completeSelectionFromIncompleteParentWarned = false;
    mTreeNodeAllocator = new TreeNodeAllocator(this);
    mGameSearcher = xiGameSearcher;

    //  For now assume players in muli-player games are somewhat irrational.
    //  FUTURE - adjust during the game based on correlations with expected
    //  scores
    for (int i = 0; i < numRoles; i++)
    {
      if (gameCharacateristics.isMultiPlayer)
      {
        roleRationality[i] = (i == 0 ? 1 : 0.8);
      }
      else
      {
        roleRationality[i] = 1;
      }
    }

    mNodeAverageScores = new double[numRoles];
    mNodeAverageSquaredScores = new double[numRoles];
    mNodeSynchronousRequest = new RolloutRequest(numRoles);
  }

  public void empty()
  {
    numUniqueTreeNodes = 0;
    numTotalTreeNodes = 0;
    numCompletedBranches = 0;
    numNormalExpansions = 0;
    numAutoExpansions = 0;
    maxAutoExpansionDepth = 0;
    averageAutoExpansionDepth = 0;
    root = null;
    nodePool.clear(mTreeNodeAllocator, true);
    positions.clear();
    numIncompleteNodes = 0;
    if (nodeMoveWeightsCache != null)
    {
      nodeMoveWeightsCache.clear();
    }
  }

  TreeNode allocateNode(ForwardDeadReckonPropnetStateMachine underlyingStateMachine,
                                ForwardDeadReckonInternalMachineState state,
                                TreeNode parent,
                                boolean disallowTransposition)
      throws GoalDefinitionException
  {
    ProfileSection methodSection = ProfileSection.newInstance("allocateNode");
    try
    {
      TreeNode result = ((state != null && !disallowTransposition) ? positions.get(state) : null);

      //validateAll();
      numTotalTreeNodes++;
      //  Use of pseudo-noops in factors can result in recreation of the root state (only)
      //  a lower level with a joint move of (pseudo-noop, noop, noop, ..., noop).  This
      //  must not be linked back to or else a loop will be created
      if (result == null || result == root)
      {
        numUniqueTreeNodes++;

        //LOGGER.debug("Add state " + state);
        result = nodePool.allocate(mTreeNodeAllocator);
        result.state = state;

        //if ( positions.values().contains(result))
        //{
        //  LOGGER.info("Node already referenced by a state!");
        //}
        if (state != null && !disallowTransposition)
        {
          positions.put(state, result);
        }
      }
      else
      {
        if (result.freed)
        {
          LOGGER.warn("Bad ref in positions table!");
        }
        if (result.decidingRoleIndex != numRoles - 1)
        {
          LOGGER.warn("Non-null move in position cache");
        }
      }

      if (parent != null)
      {
        result.parents.add(parent);

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

  public void setRootState(ForwardDeadReckonInternalMachineState state, int rootDepth) throws GoalDefinitionException
  {
    ForwardDeadReckonInternalMachineState factorState;

    if ( factor == null )
    {
      factorState = state;
    }
    else
    {
      factorState = new ForwardDeadReckonInternalMachineState(state);
      factorState.intersect(factor.getStateMask(false));
    }

    if (root == null)
    {
      root = allocateNode(underlyingStateMachine, factorState, null, false);
      root.decidingRoleIndex = numRoles - 1;
      root.setDepth(rootDepth);
    }
    else
    {
      TreeNode newRoot = root.findNode(factorState,
                                       underlyingStateMachine.getRoles()
                                           .size() + 1);
      if (newRoot == null)
      {
        LOGGER.warn("Unable to find root node in existing tree");
        empty();
        root = allocateNode(underlyingStateMachine, factorState, null, false);
        root.decidingRoleIndex = numRoles - 1;
        root.setDepth(rootDepth);
      }
      else
      {
        assert(newRoot.getDepth() == rootDepth);
        if (newRoot != root)
        {
          root.freeAllBut(newRoot);

          root = newRoot;
        }
      }
    }
    //validateAll();

    if (root.complete && root.children == null)
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

  FactorMoveChoiceInfo getBestMove()
  {
    FactorMoveChoiceInfo bestMoveInfo = root.getBestMove(true, null);

    LOGGER.info("Num total tree node allocations: " + numTotalTreeNodes);
    LOGGER.info("Num unique tree node allocations: " + numUniqueTreeNodes);
    LOGGER.info("Num true rollouts added: " + numNonTerminalRollouts);
    LOGGER.info("Num terminal nodes revisited: " + numTerminalRollouts);
    LOGGER.info("Num incomplete nodes: " + numIncompleteNodes);
    LOGGER.info("Num selections through incomplete nodes: " + numSelectionsThroughIncompleteNodes);
    LOGGER.info("Num node re-expansions: " + numReExpansions);
    LOGGER.info("Num completely explored branches: " + numCompletedBranches);
    if (numAutoExpansions + numNormalExpansions > 0)
    {
      LOGGER.info("Percentage forced single-choice expansion: " +
                  ((double)numAutoExpansions / (numAutoExpansions + numNormalExpansions)));
      LOGGER.info("Average depth of auto-expansion instances: " + averageAutoExpansionDepth);
      LOGGER.info("Maximum depth of auto-expansion instances: " + maxAutoExpansionDepth);
    }
    LOGGER.info("Current rollout sample size: " + gameCharacteristics.getRolloutSampleSize());
    LOGGER.info("Current observed rollout score range: [" +
                mGameSearcher.lowestRolloutScoreSeen + ", " +
                mGameSearcher.highestRolloutScoreSeen + "]");
    LOGGER.info("Heuristic bias: " + heuristic.getSampleWeight());

    numSelectionsThroughIncompleteNodes = 0;
    numReExpansions = 0;
    numNonTerminalRollouts = 0;
    numTerminalRollouts = 0;
    return bestMoveInfo;
  }

  void validateAll()
  {
    if (root != null)
      root.validate(true);

    for (Entry<ForwardDeadReckonInternalMachineState, TreeNode> e : positions
        .entrySet())
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

    int incompleteCount = 0;

    for (TreeNode node : nodePool.getItemTable())
    {
      if (node != null && !node.freed)
      {
        if (node.trimmedChildren > 0 && !node.complete)
        {
          incompleteCount++;
        }
        if (node.decidingRoleIndex == numRoles - 1)
        {
          if (node != positions.get(node.state))
          {
            LOGGER.warn("Missing reference in positions table");
            LOGGER.warn("node state is: " + node.state + " with hash " + node.state.hashCode());
            LOGGER.warn(positions.get(node.state));
          }
        }
      }
    }

    if (incompleteCount != numIncompleteNodes)
    {
      LOGGER.warn("Incomplete count mismatch");
    }
  }

  private void selectAction(boolean forceSynchronous) throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
    ProfileSection methodSection = ProfileSection.newInstance("TreeNode.selectAction");
    try
    {
      MoveWeightsCollection moveWeights = (gameCharacteristics.getMoveActionHistoryEnabled() ?
                                                                            new MoveWeightsCollection(numRoles) : null);

      //validateAll();
      completedNodeQueue.clear();

      //List<TreeNode> visited = new LinkedList<TreeNode>();
      TreePath visited = new TreePath(this);
      TreeNode cur = root;
      TreePathElement selected = null;
      //visited.add(this);
      while (!cur.isUnexpanded())
      {
        selected = cur.select(visited,
                              selected == null ? null : selected.getEdge(),
                              moveWeights);

        cur = selected.getChildNode();
        //visited.add(cur);
        visited.push(selected);
      }

      TreeNode newNode;
      if (!cur.complete)
      {
        //  Expand for each role so we're back to our-move as we always rollout after joint moves
        cur.expand(selected == null ? null : selected.getEdge());

        if (!cur.complete)
        {
          selected = cur.select(visited,
                                selected == null ? null : selected.getEdge(),
                                moveWeights);
          newNode = selected.getChildNode();
          //visited.add(newNode);
          visited.push(selected);

          int autoExpansionDepth = 0;

          while ((newNode.decidingRoleIndex != numRoles - 1 || newNode.autoExpand) &&
                 !newNode.complete)
          {
            if ( newNode.decidingRoleIndex == numRoles - 1 )
            {
              autoExpansionDepth++;
            }
            newNode.expand(selected.getEdge());
            if (!newNode.complete)
            {
              selected = newNode.select(visited, selected.getEdge(), moveWeights);
              newNode = selected.getChildNode();
              //visited.add(newNode);
              visited.push(selected);
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

      //  Add a pseudo-edge that represents the link into the unexplored part of the tree
      //visited.push(null);
      //validateAll();
      //LOGGER.warn("Rollout from: " + newNode.state);

      // Perform the rollout request.
      newNode.rollOut(visited, mGameSearcher.getPipeline(), forceSynchronous);
    }
    finally
    {
      methodSection.exitScope();
    }
  }
}
