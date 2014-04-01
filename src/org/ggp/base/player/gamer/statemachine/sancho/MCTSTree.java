package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import org.ggp.base.player.gamer.statemachine.sancho.TreeNode.TreeNodeRef;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.Heuristic;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class MCTSTree
{
  class LRUNodeMoveWeightsCache
  extends
  LinkedHashMap<TreeNode, MoveWeightsCollection>
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


  final boolean                                        freeCompletedNodeChildren                   = false;                                                          //true;
  final boolean                                        disableOnelevelMinimax                      = true;  //false;
  int                                                  rolloutSampleSize                           = 4;
  ForwardDeadReckonPropnetStateMachine                 underlyingStateMachine;
  volatile TreeNode                                    root = null;
  int                                                  numRoles;
  LRUNodeMoveWeightsCache                              nodeMoveWeightsCache                        = null;
  final boolean                                        enableMoveActionHistory                     = false;
  double                                               explorationBias                             = 1.0;
  double                                               moveActionHistoryBias                       = 0;
  final double                                         competitivenessBonus                        = 2;
  Map<ForwardDeadReckonInternalMachineState, TreeNode> positions                                   = new HashMap<>();
  private TreeNode[]                                   transpositionTable                          = null;
  private int                                          nextSeq                                     = 0;
  List<TreeNode>                                       freeList                                    = new LinkedList<>();
  private int                                          largestUsedIndex                            = -1;
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
  private int                                          transpositionTableSize;
  int                                                  numFreedTreeNodes                           = 0;
  int                                                  numTerminalRollouts                         = 0;
  int                                                  numUsedNodes                                = 0;
  int                                                  numIncompleteNodes                          = 0;
  int                                                  numCompletedBranches                        = 0;
  boolean                                              completeSelectionFromIncompleteParentWarned = false;
  int                                                  numSelectionsThroughIncompleteNodes         = 0;
  int                                                  numReExpansions                             = 0;
  Heuristic                                            heuristic;
  RoleOrdering                                         roleOrdering;
  RolloutProcessorPool                                 rolloutPool;
  GameCharacteristics                                  gameCharacteristics;

  public MCTSTree(ForwardDeadReckonPropnetStateMachine stateMachine,
                  int transpositionTableSize,
                  RoleOrdering roleOrdering,
                  RolloutProcessorPool rolloutPool,
                  GameCharacteristics gameCharacateristics,
                  Heuristic heuristic)
  {
    underlyingStateMachine = stateMachine;
    numRoles = stateMachine.getRoles().size();
    this.transpositionTableSize = transpositionTableSize;
    this.roleOrdering = roleOrdering;
    this.heuristic = heuristic;
    this.gameCharacteristics = gameCharacateristics;
    this.rolloutPool = rolloutPool;

    nodeMoveWeightsCache = new LRUNodeMoveWeightsCache(5000);
    transpositionTable = new TreeNode[transpositionTableSize];

    bonusBuffer = new double[numRoles];
    roleRationality = new double[numRoles];
    numCompletionsProcessed = 0;
    completeSelectionFromIncompleteParentWarned = false;

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
  }

  public void empty()
  {
    numUniqueTreeNodes = 0;
    numTotalTreeNodes = 0;
    numFreedTreeNodes = 0;
    numCompletedBranches = 0;
    numUsedNodes = 0;
    root = null;
    freeList.clear();
    for (int i = 0; i <= largestUsedIndex; i++)
    {
      transpositionTable[i].reset(true);
      freeList.add(transpositionTable[i]);
    }
    positions.clear();
    numIncompleteNodes = 0;
    if (nodeMoveWeightsCache != null)
    {
      nodeMoveWeightsCache.clear();
    }
  }

  public Object getSerializationObject()
  {
    return this;
  }

  TreeNode allocateNode(ForwardDeadReckonPropnetStateMachine underlyingStateMachine,
                                ForwardDeadReckonInternalMachineState state,
                                TreeNode parent)
      throws GoalDefinitionException
  {
    ProfileSection methodSection = new ProfileSection("allocateNode");
    try
    {
      TreeNode result = (state != null ? positions.get(state) : null);

      //validateAll();
      numTotalTreeNodes++;
      if (result == null)
      {
        numUniqueTreeNodes++;

        //System.out.println("Add state " + state);
        if (largestUsedIndex < transpositionTableSize - 1)
        {
          result = new TreeNode(this, numRoles);
          transpositionTable[++largestUsedIndex] = result;
        }
        else if (!freeList.isEmpty())
        {
          result = freeList.remove(0);

          if (!result.freed)
          {
            System.out.println("Bad allocation choice");
          }

          result.reset(false);
        }
        else
        {
          throw new RuntimeException("Unexpectedly full transition table");
        }

        result.state = state;
        result.seq = nextSeq++;

        //if ( positions.values().contains(result))
        //{
        //  System.out.println("Node already referenced by a state!");
        //}
        if (state != null)
        {
          positions.put(state, result);
        }

        numUsedNodes++;
      }
      else
      {
        if (result.freed)
        {
          System.out.println("Bad ref in positions table!");
        }
        if (result.decidingRoleIndex != numRoles - 1)
        {
          System.out.println("Non-null move in position cache");
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

      synchronized (this)
      {
        if (!node.freed)
        {
          node.processCompletion();
        }
      }
    }
  }

  public void setRootState(ForwardDeadReckonInternalMachineState state) throws GoalDefinitionException
  {
    synchronized (getSerializationObject())
    {
      //  Process anything left over from last turn's timeout
      processCompletedRollouts();

      if (root == null)
      {
        root = allocateNode(underlyingStateMachine, state, null);
        root.decidingRoleIndex = numRoles - 1;
      }
      else
      {
        TreeNode newRoot = root.findNode(state,
                                         underlyingStateMachine.getRoles()
                                             .size() + 1);
        if (newRoot == null)
        {
          System.out.println("Unable to find root node in existing tree");
          empty();
          root = allocateNode(underlyingStateMachine, state, null);
          root.decidingRoleIndex = numRoles - 1;
        }
        else
        {
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
        System.out
            .println("Encountered complete root with trimmed children - must re-expand");
        root.complete = false;
        numCompletedBranches--;
      }
    }
  }

  public boolean growTree(double explorationBias)
      throws MoveDefinitionException, TransitionDefinitionException,
      GoalDefinitionException, InterruptedException
  {
    synchronized (getSerializationObject())
    {
      this.explorationBias = explorationBias;
      while (numUsedNodes > transpositionTableSize - 200)
      {
        root.disposeLeastLikelyNode();
      }
      //validateAll();
      //validationCount++;
      if (!rolloutPool.isBackedUp())
      {
        root.selectAction();
      }

      processCompletedRollouts();

      return root.complete;
    }
  }

  Move getBestMove()
  {
    synchronized (getSerializationObject())
    {
      System.out.println("Lock obtained, current time: " +
          System.currentTimeMillis());
      Move bestMove = root.getBestMove(true, null);

      System.out.println("Num total tree node allocations: " +
          numTotalTreeNodes);
      System.out.println("Num unique tree node allocations: " +
          numUniqueTreeNodes);
      System.out.println("Num tree node frees: " + numFreedTreeNodes);
      System.out.println("Num tree nodes currently in use: " + numUsedNodes);
      System.out.println("Num true rollouts added: " +
          rolloutPool.numNonTerminalRollouts);
      System.out.println("Num terminal nodes revisited: " +
          numTerminalRollouts);
      System.out.println("Num incomplete nodes: " + numIncompleteNodes);
      System.out.println("Num selections through incomplete nodes: " +
          numSelectionsThroughIncompleteNodes);
      System.out.println("Num node re-expansions: " + numReExpansions);
      System.out.println("Num completely explored branches: " +
          numCompletedBranches);
      System.out
      .println("Current rollout sample size: " + rolloutSampleSize);
      System.out.println("Current observed rollout score range: [" +
          rolloutPool.lowestRolloutScoreSeen + ", " +
          rolloutPool.highestRolloutScoreSeen + "]");

      numSelectionsThroughIncompleteNodes = 0;
      numReExpansions = 0;
      rolloutPool.numNonTerminalRollouts = 0;
      numTerminalRollouts = 0;
      return bestMove;
    }
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
        System.out.println("Position references bad type");
      }
      if (!e.getValue().state.equals(e.getKey()))
      {
        System.out.println("Position state mismatch");
      }
    }

    int incompleteCount = 0;

    for (TreeNode node : transpositionTable)
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
            System.out.println("Missing reference in positions table");
            System.out.print("node state is: " + node.state + " with hash " +
                             node.state.hashCode());
            System.out.print(positions.get(node.state));
          }
        }
      }
    }

    if (incompleteCount != numIncompleteNodes)
    {
      System.out.println("Incomplete count mismatch");
    }
  }

  private void processCompletedRollouts()
  {
    //ProfileSection methodSection = new ProfileSection("processCompletedRollouts");
    //try
    //{
    //  Process nay outstanding node completions first, as their processing may
    //  have been interrupted due to running out of time at the end of the previous
    //  turn's processing
    processNodeCompletions();

    RolloutRequest request;

    while ((request = rolloutPool.completedRollouts.poll()) != null)
    {
      TreeNode node = request.node.node;

      //masterMoveWeights.accumulate(request.playedMoveWeights);

      if (request.node.seq == node.seq && !node.complete)
      {
        request.path.resetCursor();
        //validateAll();
        synchronized (getSerializationObject())
        {
          node.updateStats(request.averageScores,
                           request.averageSquaredScores,
                           request.sampleSize,
                           request.path,
                           false);
        }
        //validateAll();
        processNodeCompletions();
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
}
