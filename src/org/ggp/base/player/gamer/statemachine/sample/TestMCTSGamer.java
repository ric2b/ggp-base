
package org.ggp.base.player.gamer.statemachine.sample;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.profile.ProfilerContext;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.TestForwardDeadReckonPropnetStateMachine;

public class TestMCTSGamer extends SampleGamer
{
  public Role                         ourRole;

  private Random                      r                                         = new Random();
  private int                         numUniqueTreeNodes                        = 0;
  private int                         numTotalTreeNodes                         = 0;
  private int                         numFreedTreeNodes                         = 0;
  private int                         numNonTerminalRollouts                    = 0;
  private int                         numTerminalRollouts                       = 0;
  private int                         numUsedNodes                              = 0;
  private int                         numIncompleteNodes                        = 0;
  private int                         numCompletedBranches                      = 0;

  private Map<MachineState, TreeNode> positions                                 = new HashMap<MachineState, TreeNode>();

  private final int                   transpositionTableSize                    = 500000;
  private final int                   transpositinoTableMaxDesiredSizeAtTurnEnd = transpositionTableSize - 100;
  private final int                   transpositionTableMaxSizeAtProbeEnd       = transpositionTableSize - 100;
  private TreeNode[]                  transpositionTable                        = new TreeNode[transpositionTableSize];
  private int                         nextSeq                                   = 0;
  private List<TreeNode>              freeList                                  = new LinkedList<TreeNode>();
  private int                         largestUsedIndex                          = -1;
  private int                         sweepInstance                             = 0;
  private List<TreeNode>              completedNodeQueue                        = new LinkedList<TreeNode>();

  private class TreeNodeRef
  {
    public TreeNode node;
    public int      seq;

    public TreeNodeRef(TreeNode node)
    {
      this.node = node;
      this.seq = node.seq;
    }
  }

  private TreeNode allocateNode(TestForwardDeadReckonPropnetStateMachine underlyingStateMachine,
                                MachineState state,
                                Move ourMove,
                                TreeNode parent)
      throws GoalDefinitionException
  {
    ProfileSection methodSection = new ProfileSection("allocatNode");
    try
    {
      TreeNode result = (ourMove == null ? positions.get(state) : null);

      //validateAll();
      numTotalTreeNodes++;
      if (result == null)
      {
        numUniqueTreeNodes++;

        //System.out.println("Add state " + state);
        if (largestUsedIndex < transpositionTableSize - 1)
        {
          result = new TreeNode();
          transpositionTable[++largestUsedIndex] = result;
        }
        else if (!freeList.isEmpty())
        {
          result = freeList.remove(0);

          if (!result.freed)
          {
            System.out.println("Bad allocation choice");
          }

          result.reset();
          result.ourMove = ourMove;
          result.state = state;
        }
        else
        {
          throw new RuntimeException("Unexpectedly full transition table");
        }

        result.setStateAndMove(underlyingStateMachine, state, ourMove);
        result.seq = nextSeq++;

        //if ( positions.values().contains(result))
        //{
        //	System.out.println("Node already referenced by a state!");
        //}
        if (ourMove == null)
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
        if (result.ourMove != null)
        {
          System.out.println("Non-null move in position cache");
        }
      }

      if (result.ourMove != ourMove)
      {
        System.out.println("Move mismatch");
      }

      if (parent != null)
      {
        result.parents.add(parent);

        parent.adjustDescendantCounts(result.descendantCount + 1);
      }

      //validateAll();
      return result;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  private class TreeNode
  {
    static final double   epsilon           = 1e-6;

    private int           seq               = -1;
    private Move          ourMove;
    private int           numVisits         = 0;
    private double        averageScore;
    private MachineState  state;
    private boolean       isTerminal        = false;
    private TreeNodeRef[] children          = null;
    private Set<TreeNode> parents           = new HashSet<TreeNode>();
    private int           trimmedChildren   = 0;
    private int           sweepSeq;
    boolean               freed             = false;
    int                   descendantCount   = 0;
    private int           leastLikelyWinner = -1;
    private double        leastLikelyRunnerUpValue;
    private int           mostLikelyWinner  = -1;
    private double        mostLikelyRunnerUpValue;
    private boolean       complete          = false;

    private TreeNode() throws GoalDefinitionException
    {
    }

    private void setStateAndMove(TestForwardDeadReckonPropnetStateMachine underlyingStateMachine,
                                 MachineState state,
                                 Move ourMove) throws GoalDefinitionException
    {
      this.state = state;
      this.ourMove = ourMove;

      if (ourMove == null)
      {
        isTerminal = underlyingStateMachine.isTerminal(state);
        if (isTerminal)
        {
          averageScore = 100 - netScore(state);

          //System.out.println("Reached terminal state with score " + averageScore + " : "+ state);
        }
      }
      else
      {
        isTerminal = false;
      }
    }

    private void processNodeCompletions()
    {
      while (!completedNodeQueue.isEmpty())
      {
        TreeNode node = completedNodeQueue.remove(0);

        if (!node.freed)
        {
          node.processCompletion();
        }
      }
    }

    private void markComplete()
    {
      if (!complete)
      {
        complete = true;
        numCompletedBranches++;

        //System.out.println("Mark complete with score " + averageScore + (ourMove == null ? " (for opponent)" : " (for us)") + " in state: " + state);
        if (this == root)
        {
          System.out.println("Mark root complete");
        }
        else
        {
          completedNodeQueue.add(this);
        }
      }
    }

    private void processCompletion()
    {
      for (TreeNode parent : parents)
      {
        if (averageScore > 99.5)
        {
          // Win for whoever just moved after they got to choose so parent node is also decided
          parent.averageScore = 0;
          parent.markComplete();
        }
        else
        {
          //	If all children are complete then the parent is - give it a chance to
          //	decide
          parent.checkChildCompletion();
        }
      }

      //	Children can all be freed, at least from this parentage
      if (children != null)
      {
        int numDescendantsFreed = 0;

        for (TreeNodeRef cr : children)
        {
          if (cr.node.seq == cr.seq)
          {
            numDescendantsFreed += cr.node.descendantCount + 1;
            cr.node.freeFromAncestor(this);
          }
        }

        children = null;

        //	Adjust descendant count
        adjustDescendantCounts(-numDescendantsFreed);
      }
    }

    private void freeFromAncestor(TreeNode ancestor)
    {
      parents.remove(ancestor);

      if (parents.size() == 0)
      {
        if (children != null)
        {
          for (TreeNodeRef cr : children)
          {
            if (cr.node.seq == cr.seq)
            {
              cr.node.freeFromAncestor(this);
            }
          }
        }

        freeNode();
      }
    }

    private void checkChildCompletion()
    {
      boolean allChildrenComplete = true;
      double bestValue = 0;

      for (TreeNodeRef cr : children)
      {
        if (cr.node.seq == cr.seq)
        {
          if (!cr.node.complete)
          {
            allChildrenComplete = false;
          }
          else if (cr.node.averageScore > bestValue)
          {
            bestValue = cr.node.averageScore;
          }
        }
        else
        {
          allChildrenComplete = false;
        }
      }

      if (allChildrenComplete || bestValue > 99.5)
      {
        //	Opponent's choice which child to take, so take their
        //	best value and crystalize as our value
        averageScore = 100 - bestValue;
        markComplete();
      }
    }

    public void reset()
    {
      numVisits = 0;
      averageScore = 0;
      state = null;
      isTerminal = false;
      children = null;
      parents.clear();
      trimmedChildren = 0;
      freed = false;
      descendantCount = 0;
      leastLikelyWinner = -1;
      mostLikelyWinner = -1;
      complete = false;
    }

    private TreeNodeRef getRef()
    {
      return new TreeNodeRef(this);
    }

    public void adjustDescendantCounts(int adjustment)
    {
      if (freed)
      {
        System.out.println("Manipulating deleted node");
      }
      for (TreeNode parent : parents)
      {
        parent.adjustDescendantCounts(adjustment);
      }

      descendantCount += adjustment;
    }

    private int validate()
    {
      int descendants = 0;

      if (children != null)
      {
        for (TreeNodeRef cr : children)
        {
          if (cr != null)
          {
            if (cr.node.seq == cr.seq)
            {
              if (cr.node.complete && cr.node.averageScore > 99.5 &&
                  !complete && !completedNodeQueue.contains(cr.node))
              {
                System.out.println("Completeness constraint violation");
              }
              if ((cr.node.ourMove == null) != (ourMove != null))
              {
                System.out.println("Descendant type error");
              }
              descendants += cr.node.validate();
            }
          }
        }
      }

      if (descendants != descendantCount)
      {
        System.out.println("Descendant count mismatch");
      }

      return descendants + 1;
    }

    private void markTreeForSweep()
    {
      sweepSeq = sweepInstance;
      if (children != null)
      {
        for (TreeNodeRef cr : children)
        {
          if (cr.node.seq == cr.seq)
          {
            cr.node.markTreeForSweep();
          }
        }
      }
    }

    private void freeNode()
    {
      ProfileSection methodSection = new ProfileSection("TreeNode.freeNode");
      try
      {
        //validateAll();

        if (freed)
        {
          System.out.println("Freeing already free node!");
        }
        if (ourMove == null)
        {
          //if ( positions.get(state) != this )
          //{
          //	System.out.println("Position index does not point to freed node");
          //}
          positions.remove(state);
        }
        //if ( positions.containsValue(this))
        //{
        //	System.out.println("Node still referenced!");
        //}

        if (trimmedChildren > 0)
        {
          numIncompleteNodes--;
        }
        if (complete)
        {
          numCompletedBranches--;
        }

        if (children != null)
        {
          for (TreeNodeRef cr : children)
          {
            if (cr != null)
            {
              if (cr.node.seq == cr.seq)
              {
                if (cr.node.parents.size() != 0)
                {
                  cr.node.parents.remove(this);
                  if (cr.node.parents.size() == 0)
                  {
                    System.out.println("Orphaned child node");
                  }
                }
              }
            }
          }
        }

        //System.out.println("    Freeing (" + ourIndex + "): " + state);
        numFreedTreeNodes++;
        seq = -2; //	Must be negative and distinct from -1, the null ref seq value
        freeList.add(this);
        freed = true;

        numUsedNodes--;
        //validateAll();
      }
      finally
      {
        methodSection.exitScope();
      }
    }

    public void freeAllBut(TreeNode descendant)
    {
      if (descendant != null)
      {
        System.out
            .println("Free all but rooted in state: " + descendant.state);
        sweepInstance++;

        descendant.markTreeForSweep();
        descendant.parents.clear(); //	Do this here to allow generic orphan checking in node freeing
        //	without tripping over this special case
      }

      if (descendant == this || sweepSeq == sweepInstance)
      {
        //System.out.println("    Leaving: " + state);
        return;
      }

      if (children != null)
      {
        for (TreeNodeRef cr : children)
        {
          if (cr.node.seq == cr.seq)
          {
            cr.node.freeAllBut(null);
          }
        }
      }

      freeNode();
    }

    private int netScore(MachineState state) throws GoalDefinitionException
    {
      ProfileSection methodSection = new ProfileSection("TreeNode.netScore");
      try
      {
        int result = 0;
        int enemyRoleCount = 0;
        int enemyScore = 0;
        for (Role role : underlyingStateMachine.getRoles())
        {
          if (!role.equals(ourRole))
          {
            enemyRoleCount++;
            enemyScore += underlyingStateMachine.getGoal(state, role);
          }
          else
          {
            result = underlyingStateMachine.getGoal(state, role);
          }
        }

        return (result - enemyScore + 100 * enemyRoleCount) /
               (enemyRoleCount + 1);
      }
      finally
      {
        methodSection.exitScope();
      }
    }

    public TreeNode findNode(MachineState targetState)
    {
      if (state.equals(targetState) && ourMove == null)
      {
        return this;
      }

      if (children != null)
      {
        for (TreeNodeRef cr : children)
        {
          if (cr.node.seq == cr.seq)
          {
            TreeNode childResult = cr.node.findNode(targetState);
            if (childResult != null)
            {
              return childResult;
            }
          }
        }
      }

      return null;
    }

    public void disposeLeastLikelyNode()
    {
      ProfileSection methodSection = new ProfileSection("TreeNode.disposeLeastLikelyNode");
      try
      {
        TreeNode leastLikely = selectLeastLikelyNode(0);

        leastLikely.adjustDescendantCounts(-1);
        leastLikely.freeNode();
      }
      finally
      {
        methodSection.exitScope();
      }
    }

    public TreeNode selectLeastLikelyNode(int depth)
    {
      int selectedIndex = -1;
      double bestValue = -Double.MAX_VALUE;

      //System.out.println("Select LEAST in " + state);
      if (freed)
      {
        System.out.println("Encountered freed node in tree walk");
      }
      if (children != null)
      {
        if (children.length == 1)
        {
          TreeNodeRef cr = children[0];

          if (cr.node.seq == cr.seq)
          {
            selectedIndex = 0;
          }
        }
        else
        {
          if (leastLikelyWinner != -1)
          {
            TreeNodeRef cr = children[leastLikelyWinner];
            TreeNode c = cr.node;
            if (cr.seq == c.seq)
            {
              double uctValue;
              if (c.numVisits == 0)
              {
                uctValue = -1000;
              }
              else
              {
                uctValue = -c.averageScore /
                           100 -
                           Math.sqrt(Math.log(Math.max(numVisits, c.numVisits) + 1) /
                                     c.numVisits);
              }
              uctValue /= Math.log(c.descendantCount + 2); //	utcVal is negative so this makes larger subtrees score higher (less negative)

              if (uctValue >= leastLikelyRunnerUpValue)
              {
                selectedIndex = leastLikelyWinner;
              }
            }
          }

          if (selectedIndex == -1)
          {
            leastLikelyRunnerUpValue = -Double.MAX_VALUE;
            for (int i = 0; i < children.length; i++)
            {
              TreeNodeRef cr = children[i];
              if (cr != null)
              {
                TreeNode c = cr.node;
                if (c.seq != cr.seq)
                {
                  if (cr.seq != -1)
                  {
                    if (trimmedChildren++ == 0)
                    {
                      numIncompleteNodes++;
                    }
                    cr.seq = -1;
                  }
                }
                else
                {
                  if (c.freed)
                  {
                    System.out
                        .println("Encountered freed child node in tree walk");
                  }
                  double uctValue;
                  if (c.numVisits == 0)
                  {
                    uctValue = -1000;
                  }
                  //else if ( c.complete )
                  //{
                  //	Resist clearing away complete nodes as they potentially
                  //	represent a lot of work
                  //	uctValue = -500;
                  //}
                  else
                  {
                    uctValue = -c.averageScore /
                               100 -
                               Math.sqrt(Math.log(Math.max(numVisits,
                                                           c.numVisits) + 1) /
                                         c.numVisits);
                  }
                  uctValue /= Math.log(c.descendantCount + 2); //	utcVal is negative so this makes larger subtrees score higher (less negative)

                  //if ( c.isLeaf() )
                  //{
                  //	uctValue += uctValue/(depth+1);
                  //}

                  //System.out.println("  child score of " + uctValue + " in state "+ c.state);
                  if (uctValue > bestValue)
                  {
                    selectedIndex = i;
                    if (bestValue != -Double.MAX_VALUE)
                    {
                      leastLikelyRunnerUpValue = bestValue;
                    }
                    bestValue = uctValue;
                  }
                }
              }
            }
          }
        }
      }

      if (selectedIndex != -1)
      {
        leastLikelyWinner = selectedIndex;
        //System.out.println("  selected: " + selected.state);
        return children[selectedIndex].node.selectLeastLikelyNode(depth + 1);
      }

      if (descendantCount > 0)
      {
        System.out.println("Selecting non-leaf for removal!");
      }

      if (depth < 2)
        System.out.println("Selected unlikely node at depth " + depth);
      return this;
    }

    public void selectAction()
        throws MoveDefinitionException, TransitionDefinitionException,
        GoalDefinitionException
    {
      ProfileSection methodSection = new ProfileSection("TreeNode.selectAction");
      try
      {
        completedNodeQueue.clear();

        List<TreeNode> visited = new LinkedList<TreeNode>();
        TreeNode cur = this;
        visited.add(this);
        while (!cur.isUnexpanded())
        {
          cur = cur.select();
          visited.add(cur);
        }

        TreeNode newNode;
        if (!cur.complete)
        {
          cur.expand();

          if (!cur.complete)
          {
            newNode = cur.select();
            visited.add(newNode);
            if (newNode.ourMove != null)
            {
              newNode.expand();
              if (!newNode.complete)
              {
                newNode = newNode.select();
                visited.add(newNode);
              }
            }
          }
          else
          {
            newNode = cur;
          }
        }
        else
        {
          //	If we've selected a terminal node we still do a pseudo-rollout
          //	from it so it's value gets a weight increase via back propagation
          newNode = cur;
        }
        double value = newNode.rollOut();
        //validateAll();
        for (TreeNode node : visited)
        {
          node.updateStats(value);
        }
        //validateAll();

        processNodeCompletions();

        //validateAll();
      }
      finally
      {
        methodSection.exitScope();
      }
    }

    public void expand()
        throws MoveDefinitionException, TransitionDefinitionException,
        GoalDefinitionException
    {
      ProfileSection methodSection = new ProfileSection("TreeNode.expand");
      try
      {
        if (children == null || trimmedChildren > 0)
        {
          //validateAll();

          if (ourMove == null)
          {
            List<Move> moves = underlyingStateMachine.getLegalMoves(state,
                                                                    ourRole);
            TreeNodeRef[] newChildren = new TreeNodeRef[moves.size()];

            if (children != null)
            {
              int index = 0;
              for (TreeNodeRef cr : children)
              {
                TreeNode child = cr.node;
                if (cr.seq == child.seq)
                {
                  moves.remove(child.ourMove);
                  newChildren[index] = cr;
                }

                index++;
              }
            }
            for (int index = 0; index < newChildren.length; index++)
            {
              if (newChildren[index] == null)
              {
                newChildren[index] = allocateNode(underlyingStateMachine,
                                                  state,
                                                  moves.remove(0),
                                                  this).getRef();
              }
            }

            children = newChildren;
            //validateAll();
          }
          else
          {
            List<List<Move>> legalMoves = new ArrayList<List<Move>>();

            for (Role role : underlyingStateMachine.getRoles())
            {
              if (!role.equals(ourRole))
              {
                legalMoves.add(underlyingStateMachine.getLegalMoves(state,
                                                                    role));
              }
              else
              {
                List<Move> myMoveList = new ArrayList<Move>();

                myMoveList.add(ourMove);
                legalMoves.add(myMoveList);
              }
            }

            //	Now try all possible opponent moves and assume they will choose the joint worst
            List<List<Move>> jointMoves = new LinkedList<List<Move>>();

            flattenMoveLists(legalMoves, jointMoves);

            TreeNodeRef[] newChildren = new TreeNodeRef[jointMoves.size()];
            List<MachineState> newStates = new LinkedList<MachineState>();

            for (List<Move> jointMove : jointMoves)
            {
              newStates.add(underlyingStateMachine.getNextState(state,
                                                                jointMove));
            }
            if (children != null)
            {
              int index = 0;
              for (TreeNodeRef cr : children)
              {
                TreeNode child = cr.node;
                if (cr.seq == child.seq)
                {
                  newStates.remove(child.state);
                  newChildren[index] = cr;
                }

                index++;
              }
            }
            for (int index = 0; index < newChildren.length; index++)
            {
              if (newChildren[index] == null)
              {
                newChildren[index] = allocateNode(underlyingStateMachine,
                                                  newStates.remove(0),
                                                  null,
                                                  this).getRef();
              }
            }

            children = newChildren;
            //validateAll();
          }

          boolean completeChildFound = false;

          for (TreeNodeRef cr : children)
          {
            if (cr.node.seq == cr.seq)
            {
              if (cr.node.isTerminal)
              {
                cr.node.markComplete();
              }
              if (cr.node.complete)
              {
                completeChildFound = true;
              }
            }
          }

          if (completeChildFound && !complete)
          {
            checkChildCompletion();
          }
          //validateAll();
        }
      }
      finally
      {
        methodSection.exitScope();
      }
    }

    private TreeNode select()
        throws MoveDefinitionException, TransitionDefinitionException,
        GoalDefinitionException
    {
      TreeNode selected = null;
      int selectedIndex = -1;

      //System.out.println("Select in " + state);
      if (trimmedChildren == 0)
      {
        double bestValue = Double.MIN_VALUE;

        if (children != null)
        {
          if (children.length == 1)
          {
            TreeNodeRef cr = children[0];

            if (cr.node.seq == cr.seq)
            {
              selectedIndex = 0;
            }
            else
            {
              trimmedChildren = 1;
              numIncompleteNodes++;
            }
          }
          else
          {
            if (mostLikelyWinner != -1)
            {
              TreeNodeRef cr = children[mostLikelyWinner];
              TreeNode c = cr.node;
              if (cr.seq == c.seq)
              {
                double uctValue;

                if (c.numVisits == 0)
                {
                  // small random number to break ties randomly in unexpanded nodes
                  uctValue = 1000 + r.nextDouble() * epsilon;
                }
                else
                {
                  uctValue = c.averageScore /
                             100 +
                             Math.sqrt(Math.log(Math.max(numVisits,
                                                         c.numVisits) + 1) /
                                       c.numVisits);
                }

                if (uctValue >= mostLikelyRunnerUpValue)
                {
                  selectedIndex = mostLikelyWinner;
                }
              }
            }

            if (selectedIndex == -1)
            {
              mostLikelyRunnerUpValue = Double.MIN_VALUE;
              for (int i = 0; i < children.length; i++)
              {
                TreeNodeRef cr = children[i];
                if (cr != null)
                {
                  TreeNode c = cr.node;
                  if (c.seq != cr.seq)
                  {
                    if (cr.seq != -1)
                    {
                      if (trimmedChildren++ == 0)
                      {
                        numIncompleteNodes++;
                      }
                      cr.seq = -1;
                    }

                    selectedIndex = -1;
                    break;
                  }
                  double uctValue;
                  if (c.numVisits == 0)
                  {
                    // small random number to break ties randomly in unexpanded nodes
                    uctValue = 1000 + r.nextDouble() * epsilon;
                  }
                  else
                  {
                    uctValue = c.averageScore /
                               100 +
                               Math.sqrt(Math.log(Math.max(numVisits,
                                                           c.numVisits) + 1) /
                                         c.numVisits);
                  }

                  if (uctValue > bestValue)
                  {
                    selectedIndex = i;
                    if (bestValue != Double.MIN_VALUE)
                    {
                      mostLikelyRunnerUpValue = bestValue;
                    }
                    bestValue = uctValue;
                  }
                }
              }
            }
          }
        }
      }
      if (selectedIndex == -1)
      {
        if (children == null)
        {
          System.out.println("select on an unexpanded node!");
        }
        //System.out.println("  select random");
        //	pick at random.  If we pick one that has been trimmed re-expand it
        //	FUTURE - can establish a bound on the trimmed UCT value to avoid
        //	randomization for a while at least
        int childIndex = r.nextInt(children.length);
        TreeNodeRef cr = children[childIndex];
        selected = cr.node;
        if (cr.seq != selected.seq)
        {
          expand();
          if (trimmedChildren == children.length)
          {
            trimmedChildren = 0; //	This is a fresh expansion entirely can go back to full UCT
            numIncompleteNodes--;
          }
          selected = children[childIndex].node;

          if (selected.freed)
          {
            System.out.println("Selected freed node!");
          }
        }
      }
      else
      {
        mostLikelyWinner = selectedIndex;
        selected = children[selectedIndex].node;

        if (selected.freed)
        {
          System.out.println("Selected freed node!");
        }
      }

      //System.out.println("  selected: " + selected.state);
      return selected;
    }

    public boolean isUnexpanded()
    {
      return children == null || complete;
    }

    public Move getBestMove()
    {
      double bestScore = -1;
      Move result = null;

      for (TreeNodeRef cr : children)
      {
        TreeNode child = cr.node;
        System.out.println("Move " + child.ourMove + " scores " +
                           child.averageScore + " (selection count " +
                           child.numVisits +
                           (child.complete ? ", complete" : "") + ")");
        if (child.averageScore > bestScore ||
            (child.averageScore == bestScore && child.complete))
        {
          bestScore = child.averageScore;
          result = child.ourMove;
        }
      }

      return result;
    }

    public double rollOut()
        throws TransitionDefinitionException, MoveDefinitionException,
        GoalDefinitionException
    {
      if (complete)
      {
        //System.out.println("Terminal state " + state + " score " + averageScore);
        numTerminalRollouts++;
        if (ourMove == null)
        {
          return 100 - averageScore;
        }
        return averageScore;
      }
      if (ourMove != null)
      {
        System.out.println("Unexpected rollout state");
      }
      ProfileSection methodSection = new ProfileSection("TreeNode.rollOut");
      try
      {
        MachineState finalState = new MachineState(new HashSet<GdlSentence>());
        numNonTerminalRollouts++;
        underlyingStateMachine.getDepthChargeResult(state,
                                                    ourRole,
                                                    1000,
                                                    finalState,
                                                    null);

        return netScore(finalState);
      }
      finally
      {
        methodSection.exitScope();
      }
    }

    public void updateStats(double value)
    {
      double oldAverageScore = averageScore;

      if (ourMove == null)
      {
        averageScore = (averageScore * numVisits + 100 - value) / ++numVisits;
      }
      else
      {
        averageScore = (averageScore * numVisits + value) / ++numVisits;
      }

      if (complete && averageScore != oldAverageScore)
      {
        System.out.println("Unexpected update to complete node score");
      }

      leastLikelyWinner = -1;
      mostLikelyWinner = -1;
    }
  }

  private void validateAll()
  {
    if (root != null)
      root.validate();

    for (Entry<MachineState, TreeNode> e : positions.entrySet())
    {
      if (e.getValue().ourMove != null)
      {
        System.out.println("Position references bad type");
      }
      if (!e.getValue().state.equals(e.getKey()))
      {
        System.out.println("Position state mismatch");
      }
    }

    for (TreeNode node : transpositionTable)
    {
      if (node != null && !node.freed)
      {
        if (node.ourMove == null)
        {
          if (node != positions.get(node.state))
          {
            System.out.println("Missing reference in positions table");
          }
        }
      }
    }
  }

  private void emptyTree()
  {
    numUniqueTreeNodes = 0;
    numTotalTreeNodes = 0;
    numFreedTreeNodes = 0;
    numCompletedBranches = 0;
    numUsedNodes = 0;
    root = null;
    largestUsedIndex = -1;
    positions.clear();
    freeList.clear();
  }

  private TestForwardDeadReckonPropnetStateMachine underlyingStateMachine;
  private TreeNode                                 root = null;

  // This is the default State Machine
  @Override
  public StateMachine getInitialStateMachine()
  {
    GamerLogger.setFileToDisplay("StateMachine");
    //ProfilerContext.setProfiler(new ProfilerSampleSetSimple());
    underlyingStateMachine = new TestForwardDeadReckonPropnetStateMachine(2,
                                                                          getRoleName());

    emptyTree();

    return underlyingStateMachine;
  }

  @Override
  public Move stateMachineSelectMove(long timeout)
      throws TransitionDefinitionException, MoveDefinitionException,
      GoalDefinitionException
  {
    // We get the current start time
    long start = System.currentTimeMillis();
    long finishBy = timeout - 1000;
    numNonTerminalRollouts = 0;
    numTerminalRollouts = 0;

    ourRole = getRole();

    //validateAll();
    if (root == null)
    {
      root = allocateNode(underlyingStateMachine,
                          getCurrentState(),
                          null,
                          null);
    }
    else
    {
      System.out.println("Searching for new root in state: " +
                         getCurrentState());
      TreeNode newRoot = root.findNode(getCurrentState());
      if (newRoot == null)
      {
        emptyTree();

        System.out
            .println("Unexpectedly unable to find root node in existing tree");
        root = allocateNode(underlyingStateMachine,
                            getCurrentState(),
                            null,
                            null);
      }
      else
      {
        System.out.println("Freeing unreachable nodes for new state: " +
                           getCurrentState());
        root.freeAllBut(newRoot);

        root = newRoot;
      }
    }
    //validateAll();

    // We get the end time
    // It is mandatory that stop<timeout
    long stop = System.currentTimeMillis();

    if (underlyingStateMachine.isTerminal(getCurrentState()))
    {
      System.out.println("Asked to select in terminal state!");
    }

    if (root.complete)
    {
      System.out.println("Encountered complete root - must re-expand");
      root.complete = false;
      numCompletedBranches--;
    }
    int validationCount = 0;

    while (System.currentTimeMillis() < finishBy)
    {
      //validateAll();
      validationCount++;
      root.selectAction();
      //validateAll();

      while (numUsedNodes > transpositinoTableMaxDesiredSizeAtTurnEnd)
      {
        root.disposeLeastLikelyNode();
      }
    }

    //validateAll();
    List<Move> moves = underlyingStateMachine.getLegalMoves(getCurrentState(),
                                                            ourRole);
    Move bestMove = root.getBestMove();

    while (numUsedNodes > transpositionTableMaxSizeAtProbeEnd)
    {
      root.disposeLeastLikelyNode();
    }
    //validateAll();

    System.out.println("Playing move: " + bestMove);
    System.out
        .println("Num total tree node allocations: " + numTotalTreeNodes);
    System.out.println("Num unique tree node allocations: " +
                       numUniqueTreeNodes);
    System.out.println("Num tree node frees: " + numFreedTreeNodes);
    System.out.println("Num tree nodes currently in use: " + numUsedNodes);
    System.out.println("Num true rollouts added: " + numNonTerminalRollouts);
    System.out.println("Num terminal nodes revisited: " + numTerminalRollouts);
    System.out.println("Num incomplete nodes: " + numIncompleteNodes);
    System.out.println("Num completely explored branches: " +
                       numCompletedBranches);

    if (ProfilerContext.getContext() != null)
    {
      GamerLogger.log("GamePlayer", "Profile stats: \n" +
                                    ProfilerContext.getContext().toString());
    }

    /**
     * These are functions used by other parts of the GGP codebase You
     * shouldn't worry about them, just make sure that you have moves,
     * selection, stop and start defined in the same way as this example, and
     * copy-paste these two lines in your player
     */
    notifyObservers(new GamerSelectedMoveEvent(moves, bestMove, stop - start));
    return bestMove;
  }

  private void flattenMoveSubLists(List<List<Move>> legalMoves,
                                   int iFromIndex,
                                   List<List<Move>> jointMoves,
                                   List<Move> partialJointMove)
  {
    if (iFromIndex >= legalMoves.size())
    {
      jointMoves.add(new ArrayList<Move>(partialJointMove));
      return;
    }

    for (Move move : legalMoves.get(iFromIndex))
    {
      if (partialJointMove.size() <= iFromIndex)
      {
        partialJointMove.add(move);
      }
      else
      {
        partialJointMove.set(iFromIndex, move);
      }

      flattenMoveSubLists(legalMoves,
                          iFromIndex + 1,
                          jointMoves,
                          partialJointMove);
    }
  }

  private void flattenMoveLists(List<List<Move>> legalMoves,
                                List<List<Move>> jointMoves)
  {
    List<Move> partialJointMove = new ArrayList<Move>();

    flattenMoveSubLists(legalMoves, 0, jointMoves, partialJointMove);
  }

}
