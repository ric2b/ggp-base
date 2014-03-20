package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import org.ggp.base.player.gamer.statemachine.sancho.TreeNode.TreeNodeRef;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.TestForwardDeadReckonPropnetStateMachine;

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
  TestForwardDeadReckonPropnetStateMachine             underlyingStateMachine;
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
  double[]                                             decisiveCompletionRatio                     = null;
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
  boolean                                              isSimultaneousMove;
  boolean                                              isPuzzle;
  boolean                                              isMultiPlayer;
  HeuristicProvider                                    heuristicProvider;
  RoleOrdering                                         roleOrdering;
  RolloutProcessorPool                                 rolloutPool;

  public MCTSTree(TestForwardDeadReckonPropnetStateMachine stateMachine,
                  int transpositionTableSize,
                  RoleOrdering roleOrdering,
                  RolloutProcessorPool rolloutPool,
                  boolean isSimultaneousMove,
                  HeuristicProvider heuristicProvider)
  {
    underlyingStateMachine = stateMachine;
    numRoles = stateMachine.getRoles().size();
    this.transpositionTableSize = transpositionTableSize;
    this.roleOrdering = roleOrdering;
    this.heuristicProvider = heuristicProvider;
    this.isSimultaneousMove = isSimultaneousMove;
    this.rolloutPool = rolloutPool;

    isPuzzle = (numRoles == 1);
    isMultiPlayer = (numRoles > 2);

    nodeMoveWeightsCache = new LRUNodeMoveWeightsCache(5000);
    transpositionTable = new TreeNode[transpositionTableSize];

    bonusBuffer = new double[numRoles];
    roleRationality = new double[numRoles];
    decisiveCompletionRatio = new double[numRoles];
    numCompletionsProcessed = 0;
    completeSelectionFromIncompleteParentWarned = false;

    //  For now assume players in muli-player games are somewhat irrational.
    //  FUTURE - adjust during the game based on correlations with expected
    //  scores
    for (int i = 0; i < numRoles; i++)
    {
      if (isMultiPlayer)
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

  TreeNode allocateNode(TestForwardDeadReckonPropnetStateMachine underlyingStateMachine,
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
}
