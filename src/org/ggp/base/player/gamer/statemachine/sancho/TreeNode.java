package org.ggp.base.player.gamer.statemachine.sancho;

import java.io.File;
import java.io.PrintWriter;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.TreePath.TreePathElement;
import org.ggp.base.player.gamer.statemachine.sancho.pool.CappedPool;
import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool.ObjectAllocator;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;

/**
 * A node in an MCTS "tree" (actually a DAG).
 *
 * OCCUPANCY CRITICAL CLASS.
 */
public class TreeNode
{
  private static final Logger LOGGER = LogManager.getLogger();

  /**
   * An arbitrary small number to cope with rounding errors
   */
  static final double         EPSILON = 1e-4;

  static final double         scoreTemporalDecayRate = 0.99;

  /**
   * For debugging use only - enable assertion that the game is fixed sum
   */
  private static final boolean       ASSERT_FIXED_SUM = false;

  private static final DecimalFormat FORMAT_2DP = new DecimalFormat("####0.00");

  /**
   * Dummy reference value to use when a reference doesn't currently refer to a tree node.
   */
  public static final long NULL_REF = -1L;

  /**
   * Utility class for allocating tree nodes from a CappedPool.
   */
  public static class TreeNodeAllocator implements ObjectAllocator<TreeNode>
  {
    private final MCTSTree mTree;

    /**
     * Create an allocator for nodes in the the specified MCTS tree.
     *
     * @param xiTree - the tree.
     */
    public TreeNodeAllocator(MCTSTree xiTree)
    {
      mTree = xiTree;
    }

    @Override
    public TreeNode newObject(int xiPoolIndex)
    {
      TreeNode lNode = new TreeNode(mTree, xiPoolIndex);
      return lNode;
    }

    @Override
    public void resetObject(TreeNode xiNode, boolean xiFree)
    {
      xiNode.reset(xiFree ? null : mTree);
    }

    @Override
    public boolean shouldReset(TreeNode xiNode)
    {
      // Only reset items from our own tree that haven't already been freed.
      return (xiNode.tree == mTree) && (!xiNode.freed);
    }
  }

  /**
   * WARNING
   * -------
   *
   * This is an occupancy critical class.  Members should only be added when absolutely necessary and should use the
   * smallest available type.
   */

  /**
   * The tree in which we're a node.
   */
  MCTSTree tree;

  // A node reference.  This is a combination of the node's index in the allocating pool & a sequence number.  The
  // sequence number is incremented each time the node is re-used (thereby invalidating old references).
  //
  // The index is in the low 32 bits.  The sequence number is in the high 32 bits.
  //
  // For performance we also keep the instance ID in its own field.  Having been set on allocation, this never changes.
  private long                          mRef                = 0;
  private final int                     mInstanceID;

  public int                            numVisits           = 0;
  double                                numUpdates          = 0;
  final ForwardDeadReckonInternalMachineState state;
  int                                   decidingRoleIndex;
  boolean                               isTerminal          = false;
  boolean                               autoExpand          = false;
  boolean                               complete            = false;
  private boolean                       allChildrenComplete = false;
  Object[]                              children            = null;
  short                                 mNumChildren        = 0;
  short[]                               primaryChoiceMapping = null;
  final ArrayList<TreeNode>     parents             = new ArrayList<>(1);
  private int                           sweepSeq;
  //private TreeNode sweepParent = null;
  boolean                               freed               = false;
  private double                        leastLikelyRunnerUpValue;
  private double                        mostLikelyRunnerUpValue;
  private short                         leastLikelyWinner   = -1;
  private short                         mostLikelyWinner    = -1;
  //  Note - the 'depth' of a node is an indicative measure of its distance from the
  //  initial state.  However, it is not an absolute count of the path length.  This
  //  is because in some games the same state can occur at different depths (English Draughts
  //  exhibits this), which means that transitions to the same node can occur at multiple
  //  depths.  This approximate nature good enough for our current usage, but should be borne
  //  in mind if that usage is expanded.  It is initialized to -1 so that a transposition
  //  to an existing node can be distinguished from a fresh allocation
  private short                         depth               = -1;
  private short                         completionDepth;
  private double                        heuristicValue;
  private double                        heuristicWeight;

  /**
   * Create a tree node.
   *
   * @param mTree        - the tree in which this node is to be found.
   * @param xiPoolIndex - the index in the pool from which this node was allocated.
   */
  TreeNode(MCTSTree xiTree, int xiPoolIndex)
  {
    tree = xiTree;
    mRef = xiPoolIndex;
    mInstanceID = xiPoolIndex;
    state = new ForwardDeadReckonInternalMachineState(tree.underlyingStateMachine.getInfoSet());
    children = new Object[tree.gameCharacteristics.getChoicesHighWaterMark(0)];
  }

  /**
   * Set the game state represented by this node.
   *
   * @param xiState - the state.
   */
  public void setState(ForwardDeadReckonInternalMachineState xiState)
  {
    state.copy(xiState);
    //assert(mNumChildren <= 1 || state.toString().contains("control o") == (decidingRoleIndex == 1));
  }

  /**
   * Retrieve the depth of this node from the initial state
   * @return node's depth
   */
  public int getDepth()
  {
    return depth;
  }

  /**
   * Set the depth of this node from the initial state
   * @param theDepth value to set
   */
  public void setDepth(short theDepth)
  {
    depth = theDepth;
  }

  /**
   * Retrieve the depth of the best-play terminal state known to
   * derive from this node.  Valid only if the node is complete
   * @return depth of the terminal state, from the initial state
   */
  public short getCompletionDepth()
  {
    assert(complete);

    return completionDepth;
  }

  /**
   * Add the first parent to this tree node.
   *
   * Nodes can have multiple parents because the MCTS Tree isn't actually a tree at all.  It's a DAG.
   *
   * @param xiParent - the parent to add.
   */
  public void addParent(TreeNode xiParent)
  {
    parents.add(xiParent);

    // Trim off any excess array slots from the last time this node was used.
    if (parents.size() == 1)
    {
      parents.trimToSize();
    }
  }

  private boolean checkFixedSum(double[] values)
  {
    if ( !ASSERT_FIXED_SUM )
    {
      return true;
    }

    assert(values.length == tree.numRoles);
    double total = 0;
    for(int i = 0; i < tree.numRoles; i++)
    {
      total += values[i];
    }
    return (Math.abs(total-100) < EPSILON);
  }

  private boolean checkFixedSum()
  {
    if ( !ASSERT_FIXED_SUM )
    {
      return true;
    }

    double total = 0;
    for(int i = 0; i < tree.numRoles; i++)
    {
      total += getAverageScore(i);
    }
    return (Math.abs(total-100) < EPSILON);
  }

  private void correctParentsForCompletion()
  {
    //	Cannot do an a-priori correction of scores based on known child scores
    //	if heuristics are in use (at least not simply, so for now, just not)
    //if (pieceStateMaps == null)
    {
      TreeNode primaryPathParent = null;
      int mostSelectedRouteCount = 0;

      for (TreeNode parent : parents)
      {
        if (parent.numUpdates > 0)
        {
          for (short index = 0; index < parent.mNumChildren; index++)
          {
            if ( parent.primaryChoiceMapping == null || parent.primaryChoiceMapping[index] == index )
            {
              Object choice = parent.children[index];

              TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
              if (edge != null && edge.mChildRef != NULL_REF && get(edge.mChildRef) == this)
              {
                if (edge.getNumChildVisits() > mostSelectedRouteCount)
                {
                  mostSelectedRouteCount = edge.getNumChildVisits();
                  primaryPathParent = parent;
                }
                break;
              }
            }
          }
        }
      }

      if (primaryPathParent != null && !primaryPathParent.complete)
      {
        boolean propagate = true;

        //validateScoreVector(primaryPathParent.averageScores);

        double totalWeight = 0;

        for (int i = 0; i < tree.numRoles; i++)
        {
          tree.mCorrectedAverageScoresBuffer[i] = 0;
        }

        for (short index = 0; index < primaryPathParent.mNumChildren; index++)
        {
          if (primaryPathParent.primaryChoiceMapping == null || primaryPathParent.primaryChoiceMapping[index] == index)
          {
            Object choice = primaryPathParent.children[index];

            TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
            if (edge != null && edge.mChildRef != NULL_REF && get(edge.mChildRef) != null)
            {
              TreeNode lChild = get(edge.mChildRef);

              if ( lChild.numUpdates > 0 || lChild.complete )
              {
                double exploitationUct = primaryPathParent.exploitationUCT(edge, lChild.decidingRoleIndex);

                double weight = (exploitationUct + 1 / Math.log(primaryPathParent.numVisits + 1)) * lChild.numVisits +
                                                                                                                  EPSILON;
                totalWeight += weight;
                for (int i = 0; i < tree.numRoles; i++)
                {
                  tree.mCorrectedAverageScoresBuffer[i] += weight * lChild.getAverageScore(i);
                }
              }
            }
          }
        }

        for (int i = 0; i < tree.numRoles; i++)
        {
          tree.mCorrectedAverageScoresBuffer[i] /= totalWeight;
        }

        for (int i = 0; i < tree.numRoles; i++)
        {
          primaryPathParent.setAverageScore(i, tree.mCorrectedAverageScoresBuffer[i]);
        }

        assert(primaryPathParent.checkFixedSum());

        if (propagate)
        {
          primaryPathParent.correctParentsForCompletion();
        }
        //validateScoreVector(primaryPathParent.averageScores);
      }
    }
  }

  private void validateCompletionValues(double[] values)
  {
    boolean matchesAll = true;
    boolean matchesDecider = false;

    for (short index = 0; index < mNumChildren; index++)
    {
      if ( primaryChoiceMapping == null || primaryChoiceMapping[index] == index )
      {
        Object choice = children[index];

        TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
        if (edge != null && edge.mChildRef != NULL_REF && get(edge.mChildRef) != null)
        {
          TreeNode lChild = get(edge.mChildRef);
          if (lChild.complete)
          {
            if (lChild.getAverageScore(decidingRoleIndex) == values[decidingRoleIndex])
            {
              matchesDecider = true;
            }
            else
            {
              matchesAll = false;
            }
          }
          else
          {
            matchesAll = false;
          }
        }
      }
    }

    if (!matchesAll && !matchesDecider)
    {
      LOGGER.warn("Inexplicable completion!");
    }
  }

  private void markComplete(double[] values, short atCompletionDepth)
  {
    if (!complete)
    {
      //validateCompletionValues(values);
      //validateAll();
      for (int i = 0; i < tree.numRoles; i++)
      {
        setAverageScore(i, values[i]);
      }

      enactMarkComplete(atCompletionDepth);
      //validateAll();
    }
  }

  public void markComplete(TreeNode fromDeciderNode, short atCompletionDepth)
  {
    if (!complete)
    {
      //assert(state.toString().contains("control o") == (decidingRoleIndex == 1));
      //validateCompletionValues(values);
      //validateAll();
      for (int i = 0; i < tree.numRoles; i++)
      {
        setAverageScore(i, fromDeciderNode.getAverageScore(i));
      }

      enactMarkComplete(atCompletionDepth);
      //validateAll();
    }
  }

  private void enactMarkComplete(short atCompletionDepth)
  {
    assert(checkFixedSum());
    assert(linkageValid());

    if (numUpdates > 0 && tree.gameCharacteristics.isSimultaneousMove)
    {
      //validateScoreVector(averageScores);
      correctParentsForCompletion();
      //validateScoreVector(averageScores);
    }

    tree.numCompletedBranches++;
    complete = true;
    completionDepth = atCompletionDepth;

    //LOGGER.debug("Mark complete with score " + averageScore + (ourMove == null ? " (for opponent)" : " (for us)") + " in state: " + state);
    if (this == tree.root)
    {
      LOGGER.info("Mark root complete");
    }
    else
    {
      tree.completedNodeQueue.add(this);
    }
  }

  void processCompletion()
  {
    assert(linkageValid());
    //validateCompletionValues(averageScores);
    //LOGGER.debug("Process completion of node seq: " + seq);
    //validateAll();
    //	Children can all be freed, at least from this parentage
    if (MCTSTree.FREE_COMPLETED_NODE_CHILDREN)
    {
      for (int index = 0; index < mNumChildren; index++)
      {
        if ( primaryChoiceMapping == null || primaryChoiceMapping[index] == index )
        {
          Object choice = children[index];

          TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
          if (edge != null)
          {
            TreeNode lChild = (edge.mChildRef == NULL_REF ? null : get(edge.mChildRef));

            deleteEdge(index);
            if (lChild != null)
            {
              lChild.freeFromAncestor(this, null);
            }
          }
        }
      }

      freeChildren();
    }

    for (TreeNode parent : parents)
    {
      if ( !parent.complete )
      {
        boolean decidingRoleWin = false;
        boolean mutualWin = true;
        //  Because we link directly through force-move sequences the deciding role in the completed
        //  node may not b the role that chose that path from the parent - we must check what the choosing
        //  rol on the particular parent was
        int choosingRoleIndex = (parent.decidingRoleIndex+1)%tree.numRoles;

        for (int roleIndex = 0; roleIndex < tree.numRoles; roleIndex++)
        {
          tree.underlyingStateMachine.getLatchedScoreRange(parent.state, tree.roleOrdering.roleIndexToRole(roleIndex), tree.latchedScoreRangeBuffer);
          if ( tree.latchedScoreRangeBuffer[1] > tree.latchedScoreRangeBuffer[0] &&
               getAverageScore(roleIndex) > tree.latchedScoreRangeBuffer[1] - EPSILON)
          {
            if (roleIndex == choosingRoleIndex &&
                (tree.removeNonDecisionNodes || roleIndex == 0 || hasSiblinglessParents()))
            {
              decidingRoleWin = true;
              if ( tree.numRoles == 1 )
              {
                mutualWin = false;
              }
            }
          }
          else
          {
            mutualWin = false;
          }
        }

        if (decidingRoleWin && !mutualWin)
        {
          // Win for whoever just moved after they got to choose so parent node is also decided
          parent.markComplete(this, completionDepth);
        }
        else
        {
          //	If all children are complete then the parent is - give it a chance to
          //	decide
          parent.checkChildCompletion(true);
        }
      }
    }
    //validateAll();
  }

  /**
   * Delete references to all children.
   */
  public void freeChildren()
  {
    for (int lii = 0; lii < mNumChildren; lii++)
    {
      children[lii] = null;
    }
    mNumChildren = 0;
  }

  private boolean validateHasChild(TreeNode child)
  {
    boolean result = false;

    for(int i = 0; i < mNumChildren; i++)
    {
      if ( (children[i] instanceof TreeEdge) && ((TreeEdge)children[i]).mChildRef == child.getRef() )
      {
        result = true;
        break;
      }
    }

    return result;
  }

  boolean linkageValid()
  {
    if ( mNumChildren > 0 )
    {
      for(short i = 0; i < mNumChildren; i++)
      {
        if ( children[i] instanceof TreeEdge )
        {
          TreeEdge edge = (TreeEdge)children[i];

          if ( edge.mChildRef != NULL_REF )
          {
            TreeNode child = get(edge.mChildRef);

            if ( child != null )
            {
              if ( child != tree.root && !child.parents.contains(this) )
              {
                assert(false) : "child link not reflected in back-link";
                return false;
              }

              for(short j = 0; j < mNumChildren; j++)
              {
                if ( i != j && children[j] instanceof TreeEdge )
                {
                  TreeEdge edge2 = (TreeEdge)children[j];
                  TreeNode child2 = (edge2.mChildRef == NULL_REF ? null : get(edge2.mChildRef));

                  if ( child == child2 )
                  {
                    assert(false) : "multiply linked child";
                    return false;
                  }
                }
              }
            }
            else
            {
              assert(false) : "edge points to stale child";
              return false;
            }
          }
        }
      }
    }

    for(TreeNode parent : parents)
    {
      if ( !parent.validateHasChild(this) )
      {
        assert(false) : "parent missing child link";
        return false;
      }
    }
    return true;
  }

  private void freeFromAncestor(TreeNode ancestor, TreeNode xiKeep)
  {
    assert(this == xiKeep || parents.contains(ancestor));

    boolean keep = (xiKeep == null ? (parents.size() > 1) : (sweepSeq == tree.sweepInstance));

    if (keep)
    {
      // We're re-rooting the tree and have already calculated that this node (which we happen to have reached through
      // a part of the tree that's being pruned) is reachable from the new root.  Therefore, we know it needs to be
      // kept.
      assert(parents.size() != 0 || this == xiKeep) : "Oops - no link left to new root";

      parents.remove(ancestor);
      assert(linkageValid());
      return;
    }

    for (int index = 0; index < mNumChildren; index++)
    {
      if (primaryChoiceMapping == null || primaryChoiceMapping[index] == index)
      {
        Object choice = children[index];
        TreeNode lChild = null;

        TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
        if (edge != null )
        {
          lChild = (edge.mChildRef == NULL_REF ? null : get(edge.mChildRef));

          deleteEdge(index);
        }

        // Free the child (at least from us) and free our edge to it.
        if ( lChild != null && lChild != xiKeep )
        {
          lChild.freeFromAncestor(this, xiKeep);
        }
      }
    }

    freeNode();
  }

  private boolean hasSiblings()
  {
    for (TreeNode parent : parents)
    {
      for (short index = 0; index < parent.mNumChildren; index++)
      {
        if ( parent.primaryChoiceMapping == null || parent.primaryChoiceMapping[index] == index )
        {
          Object choice = parent.children[index];

          //  An unexpanded edge or child node cannot be the same as this one
          TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
          if (edge == null || edge.mChildRef == NULL_REF || get(edge.mChildRef) != this)
          {
            return true;
          }
        }
      }
    }

    return false;
  }

  private boolean hasSiblinglessParents()
  {
    for (TreeNode parent : parents)
    {
      if (parent == tree.root)
      {
        return false;
      }

      for (TreeNode grandParent : parent.parents)
      {
        if (grandParent.mNumChildren > 1)
        {
          return false;
        }
      }
    }

    return true;
  }

  public boolean hasUnexpandedChoices()
  {
    if ( children == null )
    {
      return true;
    }

    for(Object choice : children)
    {
      if ( !(choice instanceof TreeEdge) )
      {
        return true;
      }
    }

    return false;
  }

  private boolean allNephewsComplete()
  {
    for (TreeNode parent : parents)
    {
      for (short index = 0; index < parent.mNumChildren; index++)
      {
        if ( parent.primaryChoiceMapping == null || parent.primaryChoiceMapping[index] == index )
        {
          Object choice = parent.children[index];

          TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
          if (edge == null || edge.mChildRef == NULL_REF)
          {
            return false;
          }

          TreeNode child = get(edge.mChildRef);
          if (child != null)
          {
            if (!child.complete)
            {
              if (mNumChildren != 0)
              {
                for (short nephewIndex = 0; nephewIndex < child.mNumChildren; nephewIndex++)
                {
                  if ( child.primaryChoiceMapping == null || child.primaryChoiceMapping[nephewIndex] == nephewIndex )
                  {
                    Object nephewChoice = child.children[nephewIndex];

                    TreeEdge nephewEdge = (nephewChoice instanceof TreeEdge ? (TreeEdge)nephewChoice : null);
                    if (nephewEdge == null || nephewEdge.mChildRef == NULL_REF)
                    {
                      return false;
                    }

                    TreeNode nephew = get(nephewEdge.mChildRef);

                    if (nephew == null || !nephew.complete)
                    {
                      return false;
                    }
                  }
                }
              }
              else
              {
                return false;
              }
            }
          }
          else
          {
            return false;
          }
        }
      }
    }

    return true;
  }

  private void checkSiblingCompletion()
  {
    for (TreeNode parent : parents)
    {
      for (short index = 0; index < parent.mNumChildren; index++)
      {
        if ( parent.primaryChoiceMapping == null || parent.primaryChoiceMapping[index] == index )
        {
          Object choice = parent.children[index];

          TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
          if (edge != null && edge.mChildRef != NULL_REF)
          {
            TreeNode child = get(edge.mChildRef);
            if (child != null && child != this && child.mNumChildren != 0 && !child.complete)
            {
              child.checkChildCompletion(false);
            }
          }
        }
      }
    }
  }

  private boolean isBestMoveInAllUncles(Set<Move> moves, int roleIndex)
  {
    for (TreeNode parent : parents)
    {
      for (short index = 0; index < parent.mNumChildren; index++)
      {
        if ( parent.primaryChoiceMapping == null || parent.primaryChoiceMapping[index] == index )
        {
          Object choice = parent.children[index];

          TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
          if (edge == null || edge.mChildRef == NULL_REF)
          {
            return false;
          }

          TreeNode child = get(edge.mChildRef);
          if (child != this)
          {
            if (child == null || (child.mNumChildren == 0 && !child.complete))
            {
              return false;
            }

            if (!child.complete)
            {
              double bestOtherMoveScore = 0;
              double thisMoveScore = -Double.MAX_VALUE;
              for (short nephewIndex = 0; nephewIndex < child.mNumChildren; nephewIndex++)
              {
                if ( child.primaryChoiceMapping == null || child.primaryChoiceMapping[nephewIndex] == nephewIndex )
                {
                  Object nephewChoice = child.children[nephewIndex];

                  TreeEdge nephewEdge = (nephewChoice instanceof TreeEdge ? (TreeEdge)nephewChoice : null);
                  if (nephewEdge == null || nephewEdge.mChildRef == NULL_REF)
                  {
                    if (moves.contains((nephewEdge == null ? (ForwardDeadReckonLegalMoveInfo)nephewChoice : nephewEdge.mPartialMove).move))
                    {
                      return false;
                    }
                    continue;
                  }
                  TreeNode nephew = get(nephewEdge.mChildRef);
                  if (nephew != null)
                  {
                    if (moves.contains(nephewEdge.mPartialMove.move))
                    {
                      if (nephew.getAverageScore(roleIndex) > thisMoveScore)
                      {
                        thisMoveScore = nephew.getAverageScore(roleIndex);
                      }
                    }
                    else
                    {
                      if (nephew.getAverageScore(roleIndex) > bestOtherMoveScore)
                      {
                        bestOtherMoveScore = nephew.getAverageScore(roleIndex);
                      }
                    }
                  }
                }
              }

              if (bestOtherMoveScore > thisMoveScore && thisMoveScore != -Double.MAX_VALUE)
              {
                return false;
              }
            }
            else if (child.getAverageScore(roleIndex) < 100-EPSILON)
            {
              return false;
            }
          }
        }
      }
    }

    return true;
  }

  private TreeNode worstCompleteCousin(Move move, int roleIndex)
  {
    TreeNode result = null;

    for (TreeNode parent : parents)
    {
      for (short index = 0; index < parent.mNumChildren; index++)
      {
        if ( parent.primaryChoiceMapping == null || parent.primaryChoiceMapping[index] == index )
        {
          Object choice = parent.children[index];

          TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
          if (edge == null || edge.mChildRef == NULL_REF)
          {
            return null;
          }
          TreeNode child = get(edge.mChildRef);

          if (child == null || (child.mNumChildren == 0 && !child.complete))
          {
            return null;
          }

          if (!child.complete)
          {
            for (short nephewIndex = 0; nephewIndex < child.mNumChildren; nephewIndex++)
            {
              Object nephewChoice = child.children[nephewIndex];
              TreeEdge nephewEdge = (nephewChoice instanceof TreeEdge ? (TreeEdge)nephewChoice : null);
              ForwardDeadReckonLegalMoveInfo nephewMove = (nephewEdge == null ? (ForwardDeadReckonLegalMoveInfo)nephewChoice : nephewEdge.mPartialMove);

              if (move == nephewMove.move)
              {
                Object primaryChoice = (child.primaryChoiceMapping == null ? nephewChoice : child.children[child.primaryChoiceMapping[nephewIndex]]);

                nephewEdge = (primaryChoice instanceof TreeEdge ? (TreeEdge)primaryChoice : null);
                if (nephewEdge == null || nephewEdge.mChildRef == NULL_REF)
                {
                  return null;
                }

                TreeNode nephew = get(nephewEdge.mChildRef);
                if (nephew != null)
                {
                  if (!nephew.complete)
                  {
                    return null;
                  }
                  if (result == null ||
                      nephew.getAverageScore(roleIndex) < result.getAverageScore(roleIndex))
                  {
                    result = nephew;
                  }
                }
                else
                {
                  return null;
                }
              }
            }
          }
          else if (result == null ||
              child.getAverageScore(roleIndex) < result.getAverageScore(roleIndex))
          {
            result = child;
          }
        }
      }
    }

    return result;
  }

  @SuppressWarnings("null")
  private void checkChildCompletion(boolean checkConsequentialSiblingCompletion)
  {
    boolean allImmediateChildrenComplete = true;
    double bestValue = -1000;
    TreeNode bestValueNode = null;
    int roleIndex = (decidingRoleIndex + 1) % tree.numRoles;
    boolean decidingRoleWin = false;
    TreeNode worstDeciderNode = null;
    TreeNode floorDeciderNode = null;
    short determiningChildCompletionDepth = Short.MAX_VALUE;
    boolean siblingCheckNeeded = false;
    double selectedOurScore = Double.MAX_VALUE;

    if ( this == tree.root && mNumChildren == 1 )
    {
      System.out.println("!");
    }
    for (int i = 0; i < tree.numRoles; i++)
    {
      tree.mNodeAverageScores[i] = 0;

      tree.underlyingStateMachine.getLatchedScoreRange(state, tree.roleOrdering.roleIndexToRole(i), tree.latchedScoreRangeBuffer);
      tree.roleMaxScoresBuffer[i] = tree.latchedScoreRangeBuffer[1];
    }

    int numUniqueChildren = 0;

    for (int index = 0; index < mNumChildren; index++)
    {
      if (primaryChoiceMapping == null || primaryChoiceMapping[index] == index)
      {
        Object choice = children[index];

        TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);

        if (edge == null)
        {
          assert(choice instanceof ForwardDeadReckonLegalMoveInfo);

          //  Pseudo-noops are not searched and do not form 'real' moves in this factor
          //  so ignore them for completion propagation purposes
          if ( !((ForwardDeadReckonLegalMoveInfo)choice).isPseudoNoOp )
          {
            allImmediateChildrenComplete = false;
          }
        }
        else
        {
          if (edge.mChildRef == NULL_REF)
          {
            allImmediateChildrenComplete = false;
          }
          else if (get(edge.mChildRef) != null)
          {
            TreeNode lNode = get(edge.mChildRef);
            numUniqueChildren++;

            if (!lNode.complete)
            {
              allImmediateChildrenComplete = false;
            }
            else
            {
              if (worstDeciderNode == null || lNode.getAverageScore(roleIndex) < worstDeciderNode.getAverageScore(roleIndex))
              {
                worstDeciderNode = lNode;
              }

              //  In the event of several choices having the same score for the deciding role
              //  assume that the one with the worst score for us will be chosen
              double deciderScore = lNode.getAverageScore(roleIndex);
              if ( deciderScore > bestValue || (deciderScore == bestValue && roleIndex != 0 && lNode.getAverageScore(0) < selectedOurScore))
              {
                selectedOurScore = lNode.getAverageScore(0);
                bestValue = deciderScore;
                bestValueNode = lNode;

                if (bestValue > tree.roleMaxScoresBuffer[roleIndex]-EPSILON)
                {
                  //	Win for deciding role which they will choose unless it is also
                  //	a mutual win
                  boolean mutualWin = true;

                  for (int i = 0; i < tree.numRoles; i++)
                  {
                    if (lNode.getAverageScore(i) < tree.roleMaxScoresBuffer[i]-EPSILON)
                    {
                      if (determiningChildCompletionDepth > lNode.getCompletionDepth())
                      {
                        determiningChildCompletionDepth = lNode.getCompletionDepth();
                      }
                      mutualWin = false;
                      break;
                    }
                  }

                  if (!decidingRoleWin)
                  {
                    decidingRoleWin |= !mutualWin;

                    if (decidingRoleWin && tree.gameCharacteristics.isSimultaneousMove)
                    {
                      //	Only complete on this basis if this move is our choice (complete info)
                      //	or wins in ALL cousin states also
                      if (roleIndex != 0 && hasSiblings())
                      {
                        Set<Move> equivalentMoves = new HashSet<>();

                        if ( primaryChoiceMapping == null )
                        {
                          equivalentMoves.add(edge.mPartialMove.move);
                        }
                        else
                        {
                          for (short siblingIndex = 0; siblingIndex < mNumChildren; siblingIndex++)
                          {
                            if ( primaryChoiceMapping[siblingIndex] == index )
                            {
                              if ( siblingIndex == index )
                              {
                                assert(children[siblingIndex] instanceof TreeEdge);
                                equivalentMoves.add(((TreeEdge)children[siblingIndex]).mPartialMove.move);
                              }
                              else
                              {
                                assert(children[siblingIndex] instanceof ForwardDeadReckonLegalMoveInfo);
                                equivalentMoves.add(((ForwardDeadReckonLegalMoveInfo)children[siblingIndex]).move);
                              }
                            }
                          }
                        }
                        if (!isBestMoveInAllUncles(equivalentMoves, roleIndex))
                        {
                          decidingRoleWin = false;
                        }
                        else
                        {
                          if (checkConsequentialSiblingCompletion)
                          {
                            siblingCheckNeeded = true;
                          }
                        }
                      }
                    }
                  }
                }
              }

              if (tree.gameCharacteristics.isSimultaneousMove &&
                  !decidingRoleWin &&
                  roleIndex != 0 &&
                  (floorDeciderNode == null || floorDeciderNode.getAverageScore(roleIndex) < lNode.getAverageScore(roleIndex)))
              {
                //	Find the highest supported floor score for any of the moves equivalent to this one
                TreeNode worstCousinValueNode = null;
                short floorCompletionDepth = Short.MAX_VALUE;

                for (short siblingIndex = 0; siblingIndex < mNumChildren; siblingIndex++)
                {
                  if ( siblingIndex == index || (primaryChoiceMapping != null && primaryChoiceMapping[siblingIndex] == index) )
                  {
                    Object siblingChoice = children[siblingIndex];
                    ForwardDeadReckonLegalMoveInfo siblingMove = (siblingChoice instanceof ForwardDeadReckonLegalMoveInfo) ? (ForwardDeadReckonLegalMoveInfo)siblingChoice : ((TreeEdge)siblingChoice).mPartialMove;
                    TreeNode moveFloorNode = worstCompleteCousin(siblingMove.move, roleIndex);

                    if (moveFloorNode != null)
                    {
                      if (worstCousinValueNode == null ||
                          worstCousinValueNode.getAverageScore(roleIndex) < moveFloorNode.getAverageScore(roleIndex))
                      {
                        worstCousinValueNode = moveFloorNode;
                        if (floorCompletionDepth > lNode.getCompletionDepth())
                        {
                          floorCompletionDepth = lNode.getCompletionDepth();
                        }
                      }
                    }
                  }
                }

                if (worstCousinValueNode != null &&
                    (floorDeciderNode == null || floorDeciderNode.getAverageScore(roleIndex) < worstCousinValueNode.getAverageScore(roleIndex)))
                {
                  floorDeciderNode = worstCousinValueNode;
                  determiningChildCompletionDepth = floorCompletionDepth;
                }
              }
            }

            for (int i = 0; i < tree.numRoles; i++)
            {
              tree.mNodeAverageScores[i] += lNode.getAverageScore(i);
            }
          }
          else
          {
            allImmediateChildrenComplete = false;
          }
        }
      }
    }

    for (int i = 0; i < tree.numRoles; i++)
    {
      tree.mNodeAverageScores[i] /= numUniqueChildren;
    }

    if (allImmediateChildrenComplete && !decidingRoleWin &&
        tree.gameCharacteristics.isSimultaneousMove && roleIndex != 0)
    {
      allChildrenComplete = true;

      //	If the best we can do from this node is no better than the supported floor we
      //	don't require all nephews to be complete to complete this node at the floor
      if (!hasSiblings() ||
          (floorDeciderNode != null && floorDeciderNode.getAverageScore(roleIndex) +
          EPSILON >= bestValueNode.getAverageScore(roleIndex)))
      {
        //	There was only one opponent choice so this is not after all
        //	incomplete information, so complete with the best choice for
        //	the decider
        decidingRoleWin = true;
      }
      else
      {
        //	To auto complete with simultaneous turn and no deciding role win
        //	we require that all nephews be complete or that all alternatives
        //	are anyway equivalent
        boolean allNephewsComplete = allNephewsComplete();

        for (int i = 0; i < tree.numRoles; i++)
        {
          if (Math.abs(tree.mNodeAverageScores[i] - bestValueNode.getAverageScore(i)) > EPSILON)
          {
            allImmediateChildrenComplete = allNephewsComplete;

            break;
          }
        }
      }

      if (allImmediateChildrenComplete &&
          checkConsequentialSiblingCompletion)
      {
        siblingCheckNeeded = true;
      }

      //  It is possible for all children to be terminal, in which case no values will
      //  ever have been propagated back from them (as we don't rollout from a terminal state)
      //  In a simultaneous move game such a situation will result in this node's average score
      //  not being set initially, since completion will not occur until all nephew's complete.
      //  We address this case by setting the scores provisionally as an average of the children (but
      //  not marking complete yet)
      if ( !allImmediateChildrenComplete && numUpdates == 0 )
      {
        for (int i = 0; i < tree.numRoles; i++)
        {
          setAverageScore(i, tree.mNodeAverageScores[i]);
        }
      }
    }

    if (allImmediateChildrenComplete || decidingRoleWin)
    {
      if (determiningChildCompletionDepth == Short.MAX_VALUE)
      {
        //  If there was no winning choice but everything is complete then
        //  the depth is the maximum of the non-winning choice alternatives.
        //  Note - this may be slightly misleading for non-fixed-sum games
        //  that are expected to end at intermediate score values, but should
        //  operate correctly in other cases, and give reasonable indicative
        //  results in all cases
        determiningChildCompletionDepth = 0;

        for (short index = 0; index < mNumChildren; index++)
        {
          if ( primaryChoiceMapping == null || primaryChoiceMapping[index] == index )
          {
            Object choice = children[index];

            TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
            if ( edge != null )
            {
              if (edge.mChildRef != NULL_REF && get(edge.mChildRef) != null)
              {
                TreeNode lNode = get(edge.mChildRef);
                if (determiningChildCompletionDepth < lNode.getCompletionDepth())
                {
                  determiningChildCompletionDepth = lNode.getCompletionDepth();
                }
              }
            }
          }
        }
      }

      tree.numCompletionsProcessed++;

      //	Opponent's choice which child to take, so take their
      //	best value and crystalize as our value.   However, if it's simultaneous
      //	move complete with the average score since
      //	opponents cannot make the pessimal (for us) choice reliably
      if (tree.gameCharacteristics.isSimultaneousMove && !decidingRoleWin)
      {
        //	If all option are complete but not decisive due to incomplete information
        //  arising form the opponent's simultaneous turn then take a weighted average
        //  of the child node scores, weighting them by the average cousin value of the
        //  deciding role
        if ( roleIndex != 0 )
        {
          tree.cousinMovesCachedFor = NULL_REF;

          //  Weight the values by their average cousin score
          double totalWeight = 0;
          for (int i = 0; i < tree.numRoles; i++)
          {
            tree.mBlendedCompletionScoreBuffer[i] = 0;
          }

          for (short index = 0; index < mNumChildren; index++)
          {
            if ( primaryChoiceMapping == null || primaryChoiceMapping[index] == index )
            {
              Object choice = children[index];

              //  Pseudo-noops in factored games can still be unexpanded at this point
              if ( choice instanceof TreeEdge )
              {
                TreeEdge edge = (TreeEdge)choice;

                assert(edge.mChildRef != NULL_REF);

                TreeNode lNode = get(edge.mChildRef);
                assert(lNode != null);
                assert(lNode.complete);

                //  Add epsilon in case all are 0
                double chooserScore = getAverageCousinMoveValue(edge, roleIndex) + EPSILON;

                for (int i = 0; i < tree.numRoles; i++)
                {
                  tree.mBlendedCompletionScoreBuffer[i] += chooserScore*lNode.getAverageScore(i);
                }
                totalWeight += chooserScore;
              }
            }
          }

          for (int i = 0; i < tree.numRoles; i++)
          {
            tree.mBlendedCompletionScoreBuffer[i] /= totalWeight;
          }

          //  If a move provides a better-than-worst case in all uncles it provides a support
          //  floor the the worst that we can do with perfect play, so use that if its larger than
          //  what we would otherwise use
          if (floorDeciderNode != null &&
              floorDeciderNode.getAverageScore(roleIndex) > tree.mBlendedCompletionScoreBuffer[roleIndex])
          {
            for (int i = 0; i < tree.numRoles; i++)
            {
              tree.mBlendedCompletionScoreBuffer[i] = floorDeciderNode.getAverageScore(i);
            }
          }
        }
        else
        {
          //  For the final role we're transitioning to an actual fully decided new state so the
          //  appropriate choice is the best one for the chooser
          for (int i = 0; i < tree.numRoles; i++)
          {
            tree.mBlendedCompletionScoreBuffer[i] = bestValueNode.getAverageScore(i);
          }
        }
        markComplete(tree.mBlendedCompletionScoreBuffer, determiningChildCompletionDepth);
      }
      else
      {
        markComplete(bestValueNode, determiningChildCompletionDepth);
      }

      if ( siblingCheckNeeded )
      {
        checkSiblingCompletion();
      }
    }

    mostLikelyWinner = -1;
  }

  /**
   * Reset a node ready for re-use.
   *
   * @param xiTree - the tree in which the node is to be re-used (or null if not yet known).
   */
  public void reset(MCTSTree xiTree)
  {
    // Throughout this function, we do our best to reset existing objects wherever possible, rather than discarding the
    // old ones and allocating new ones.  The reduces the GC burden.

    // Increment the sequence number for this node so that any remaining TreeNodeRefs pointing to the previous
    // incarnation can spot that we've re-used this node under their feet.
    mRef += 0x100000000L;

    // Reset primitives.
    numVisits = 0;
    numUpdates = 0;
    mNumChildren = 0;
    isTerminal = false;
    autoExpand = false;
    leastLikelyWinner = -1;
    mostLikelyWinner = -1;
    complete = false;
    allChildrenComplete = false;
    freed = (xiTree == null);
    depth = -1;
    sweepSeq = 0;
    //sweepParent = null;
    heuristicValue = 0;
    heuristicWeight = 0;

    // Reset objects (without allocating new ones).
    tree = xiTree;
    parents.clear();
    state.clear();
    freeChildren();

    // Reset score values
    if ( xiTree != null )
    {
      for (int i = 0; i < xiTree.numRoles; i++)
      {
        setAverageScore(i, 0);
        setAverageSquaredScore(i, 0);
      }
    }

    // Reset remaining objects.  These will need to be re-allocated later.  That's a shame, because it produces
    // unnecessary garbage, but sorting it won't be easy.
    primaryChoiceMapping = null;
  }

  long getRef()
  {
    return mRef;
  }

  public double getAverageScore(int roleIndex)
  {
    return tree.scoreVectorPool.getAverageScore(mInstanceID, roleIndex);
  }

  public void setAverageScore(int roleIndex, double value)
  {
    assert(-EPSILON<=value);
    assert(100+EPSILON>=value);
    tree.scoreVectorPool.setAverageScore(mInstanceID, roleIndex, value);
  }

  public double getAverageSquaredScore(int roleIndex)
  {
    return tree.scoreVectorPool.getAverageSquaredScore(mInstanceID, roleIndex);
  }

  public void setAverageSquaredScore(int roleIndex, double value)
  {
    tree.scoreVectorPool.setAverageSquaredScore(mInstanceID, roleIndex, value);
  }

  void validate(boolean recursive)
  {
    for (short index = 0; index < mNumChildren; index++)
    {
      if ( primaryChoiceMapping == null || primaryChoiceMapping[index] == index )
      {
        Object choice = children[index];

        TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
        if ( edge != null )
        {
          TreeNode lNode = get(edge.mChildRef);
          if (lNode != null)
          {
            if (!lNode.parents.contains(this))
            {
              LOGGER.error("Missing parent link");
            }
            if (lNode.complete &&
                lNode.getAverageScore(decidingRoleIndex) > 100-EPSILON &&
                !complete && !tree.completedNodeQueue.contains(lNode))
            {
              LOGGER.error("Completeness constraint violation");
            }
            if ((lNode.decidingRoleIndex) == decidingRoleIndex && tree.gameCharacteristics.numRoles != 1)
            {
              LOGGER.error("Descendant type error");
            }

            if (recursive)
            {
              lNode.validate(true);
            }
          }
        }
      }
    }

    if (parents.size() > 0)
    {
      int numInwardVisits = 0;

      for (TreeNode parent : parents)
      {
        for (short index = 0; index < parent.mNumChildren; index++)
        {
          if ( parent.primaryChoiceMapping == null || parent.primaryChoiceMapping[index] == index )
          {
            Object choice = parent.children[index];

            TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
            if (edge != null && edge.mChildRef != NULL_REF && get(edge.mChildRef) == this)
            {
              numInwardVisits += edge.getNumChildVisits();
              break;
            }
          }
        }
      }

      if (numInwardVisits > numVisits)
      {
        LOGGER.error("Linkage counts do not add up");
      }
    }
  }

  /**
   * Mark all the nodes that will be in the live part of the graph with a sequence number.  When clearing out the graph,
   * if we meet a marked node, we know that it can't be deleted because it's still reachable.
   */
  private void markTreeForSweep(TreeNode parent)
  {
    assert(parent == null || parents.contains(parent)) : "Marked node for sweep from unexpected parent";
    if (sweepSeq != tree.sweepInstance)
    {
      //sweepParent = parent;
      sweepSeq = tree.sweepInstance;
      for (short index = 0; index < mNumChildren; index++)
      {
        if ( primaryChoiceMapping == null || primaryChoiceMapping[index] == index )
        {
          Object choice = children[index];

          TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
          if (edge != null && edge.mChildRef != NULL_REF && get(edge.mChildRef) != null)
          {
            get(edge.mChildRef).markTreeForSweep(this);
          }
        }
      }
    }
  }

  /**
   * Free this node.
   *
   * Before calling this method, the caller must have freed all children for whom this is the only parent.
   */
  private void freeNode()
  {
    ProfileSection methodSection = ProfileSection.newInstance("TreeNode.freeNode");
    try
    {
      //validateAll();

      assert (!freed) : "Attempt to free a node that has already been freed";

      tree.nodeFreed(this);

      if (complete)
      {
        tree.numCompletedBranches--;
      }

      // LOGGER.debug("    Freeing (" + ourIndex + "): " + state);
      freed = true;
      tree.nodePool.free(this);
      mRef += 0x100000000L;
      //validateAll();
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  public void freeAllBut(TreeNode descendant)
  {
    LOGGER.info("Free all but rooted in state: " + descendant.state);
    //tree.oldRoot.dumpTree("c:\\temp\\oldTree.txt");
    //tree.root.dumpTree("c:\\temp\\newTree.txt");

    int numNodesInUseBeforeTrim = tree.nodePool.getNumItemsInUse();

    // Mark the live portions of the tree.  This allows us to tidy up the state without repeatedly visiting live parts
    // of the tree.
    tree.sweepInstance++;
    descendant.markTreeForSweep(null);
    descendant.parents.clear(); //	Do this here to allow generic orphan checking in node freeing
                                //	without tripping over this special case

    for (int index = 0; index < mNumChildren; index++)
    {
      if (primaryChoiceMapping == null || primaryChoiceMapping[index] == index)
      {
        Object choice = children[index];

        TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
        if (edge != null &&
            edge.mChildRef != NULL_REF &&
            get(edge.mChildRef) != null)
        {
          TreeNode lNode = get(edge.mChildRef);

          // Delete our edge to the child anyway.  (We only set "descendant" when re-rooting the tree.  In that case,
          // we don't need the edge any more.)
          deleteEdge(index);

          // Free the child (at least from us)
          lNode.freeFromAncestor(this, descendant);
        }
      }
    }

    freeNode();
    tree.sweepInstance++;

    int numNodesInUseAfterTrim = tree.nodePool.getNumItemsInUse();

    LOGGER.info("Freed " + (100*(numNodesInUseBeforeTrim-numNodesInUseAfterTrim))/numNodesInUseBeforeTrim + "% of allocated nodes (" + (numNodesInUseBeforeTrim-numNodesInUseAfterTrim) + " of " + numNodesInUseBeforeTrim + ")");
  }

  private void deleteEdge(int xiChildIndex)
  {
    assert(children[xiChildIndex] instanceof TreeEdge) : "Asked to delete a non-edge";
    TreeEdge lEdge = (TreeEdge)children[xiChildIndex];

    // Replace the edge with its move (so that it can be re-expanded later if required).
    children[xiChildIndex] = lEdge.mPartialMove;

    // Return the edge to the pool.
    tree.edgePool.free(lEdge);
  }

  /**
   * Find an extant node in the tree (if any) for a specified state
   * within a given depth of the root
   * @param targetState
   * @param maxDepth
   * @return matching node or null
   */
  public TreeNode findNode(ForwardDeadReckonInternalMachineState targetState, int maxDepth)
  {
    if (state.equals(targetState) && decidingRoleIndex == tree.numRoles - 1)
    {
      return this;
    }
    else if (maxDepth == 0)
    {
      return null;
    }

    for (short index = 0; index < mNumChildren; index++)
    {
      if ( primaryChoiceMapping == null || primaryChoiceMapping[index] == index )
      {
        Object choice = children[index];

        TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
        if (edge != null)
        {
          if (edge.mChildRef != NULL_REF && get(edge.mChildRef) != null)
          {
            TreeNode childResult = get(edge.mChildRef).findNode(targetState, maxDepth - 1);
            if (childResult != null)
            {
              return childResult;
            }
          }
        }
      }
    }

    return null;
  }

  public boolean disposeLeastLikelyNode()
  {
    ProfileSection methodSection = ProfileSection.newInstance("TreeNode.disposeLeastLikelyNode");
    try
    {
      TreeEdge leastLikely = selectLeastLikelyExpandedNode(null);

      if (leastLikely != null)
      {
        //  Free the children of the chosen node from its parentage
        //  and de-expand it
        leastLikely.setHasBeenTrimmed();
        get(leastLikely.mChildRef).unexpand();
        //validateAll();

        return true;
      }

      return false;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  private void unexpand()
  {
    assert(mNumChildren != 0);

    for (int index = 0; index < mNumChildren; index++)
    {
      if (primaryChoiceMapping == null || primaryChoiceMapping[index] == index)
      {
        Object choice = children[index];
        if ( choice instanceof TreeEdge )
        {
          TreeNode child = (((TreeEdge)choice).mChildRef == NULL_REF ? null : get(((TreeEdge)choice).mChildRef));

          deleteEdge(index);

          if ( child != null )
          {
            child.freeFromAncestor(this, null);
          }
        }
      }
    }

    freeChildren();
  }

  private static int leastLikelyDisposalCount = 0;
  public TreeEdge selectLeastLikelyExpandedNode(TreeEdge from)
  {
    int selectedIndex = -1;
    double bestValue = -Double.MAX_VALUE;

    //	Find the role this node is choosing for
    int roleIndex = (decidingRoleIndex + 1) % tree.numRoles;

    tree.cousinMovesCachedFor = NULL_REF;

    //validateAll();
    if (freed)
    {
      LOGGER.warn("Encountered freed node in tree walk");
    }
    if (mNumChildren != 0)
    {
      if (mNumChildren == 1)
      {
        Object choice = children[0];

        if ( choice instanceof TreeEdge )
        {
          long cr = ((TreeEdge)choice).mChildRef;
          //  Don't descend into unexpanded nodes
          if (cr != NULL_REF && get(cr) != null && !get(cr).isUnexpanded())
          {
            selectedIndex = 0;
          }
        }
      }
      else
      {
        if (leastLikelyWinner != -1)
        {
          Object choice = children[leastLikelyWinner];

          if ( choice instanceof TreeEdge )
          {
            TreeEdge edge = (TreeEdge)choice;
            long cr = edge.mChildRef;
            if (cr != NULL_REF)
            {
              TreeNode c = get(cr);
              if (c != null)
              {
                //  Don't descend into unexpanded nodes
                if (!c.isUnexpanded())
                {
                  double uctValue;
                  if (edge.getNumChildVisits() == 0 )
                  {
                    uctValue = -1000;
                  }
                  else
                  {
                    uctValue = -explorationUCT(numVisits,
                                               edge,
                                               roleIndex) -
                                               exploitationUCT(edge, roleIndex);
                  }
                  //  Add a small amount of noise to cause the subtrees we prune from
                  //  to spread around amongst reasonable candidates rather than pruning
                  //  entire subtrees which will quickly back up to low depths
                  //  in the tree which are more likely to require re-expansion
                  uctValue += tree.r.nextDouble()/20;

                  if (uctValue >= leastLikelyRunnerUpValue)
                  {
                    selectedIndex = leastLikelyWinner;
                  }
                }
              }
            }
          }
        }


        if (selectedIndex == -1)
        {
          leastLikelyRunnerUpValue = -Double.MAX_VALUE;
          for (int i = 0; i < mNumChildren; i++)
          {
            Object choice = children[i];

            if (choice instanceof TreeEdge)
            {
              TreeEdge edge = (TreeEdge)choice;
              long cr = edge.mChildRef;
              if (cr != NULL_REF)
              {
                TreeNode c = get(cr);
                //  Note - if the node has been trimmed we must not delete the edge
                //  because an unexpended edge asserts non-terminality of its child which
                //  we do not know.  Only selecting back through this edge can establish
                //  correct semantics
                if (c != null)
                {
                  if (c.freed)
                  {
                    LOGGER.warn("Encountered freed child node in tree walk");
                  }
                  //  Don't descend into unexpanded nodes
                  if (!c.isUnexpanded())
                  {
                    double uctValue;
                    if (edge.getNumChildVisits() == 0)
                    {
                      uctValue = -1000;
                    }
                    else
                    {
                      uctValue = -explorationUCT(numVisits,
                                                 edge,
                                                 roleIndex) -
                                                 exploitationUCT(edge, roleIndex);
                    }
                    //  Add a small amount of noise to cause the subtrees we prune from
                    //  to spread around amongst reasonable candidates rather than pruning
                    //  entire subtrees which will quickly back up to low depths
                    //  in the tree which are more likely to require re-expansion
                    uctValue += tree.r.nextDouble()/20;

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
      }
    }

    //validateAll();
    if (selectedIndex != -1)
    {
      leastLikelyWinner = (short)selectedIndex;
      assert(children[selectedIndex] instanceof TreeEdge);
      TreeEdge selectedEdge = (TreeEdge)children[selectedIndex];

      return get(selectedEdge.mChildRef).selectLeastLikelyExpandedNode(selectedEdge);
    }

    //  Children of the root should never be trimmed.  For us to have wanted to unexpand
    //  the root all its children must be unexpanded.  This is possible in factored games
    //  where one factor has a complete root, so all node allocation occurs in the other
    //  factor(s)'s tree(s), so it is only a warned condition at this level
    if (from == null)
    {
      if ( tree.factor == null )
      {
        LOGGER.warn("Attempt to trim child of root");
      }
    }

    return from;
  }

  private StateInfo calculateTerminalityAndAutoExpansion(ForwardDeadReckonInternalMachineState theState)
  {
    StateInfo result = StateInfo.bufferInstance;

    result.isTerminal = false;
    result.autoExpand = false;

    // Check if the goal value is latched.
    if ( /*tree.numRoles == 1 &&*/ tree.underlyingStateMachine.scoresAreLatched(theState))
    {
      result.isTerminal = true;

      for(int i = 0; i < tree.numRoles; i++)
      {
        tree.underlyingStateMachine.getLatchedScoreRange(theState, tree.roleOrdering.roleIndexToRole(i), tree.latchedScoreRangeBuffer);

        assert(tree.latchedScoreRangeBuffer[0] == tree.latchedScoreRangeBuffer[1]);
        result.terminalScore[i] = tree.latchedScoreRangeBuffer[0];
      }
    }
    else if (tree.searchFilter.isFilteredTerminal(theState))
    {
      result.isTerminal = true;

      for (int i = 0; i < tree.numRoles; i++)
      {
        result.terminalScore[i] = tree.underlyingStateMachine.getGoal(tree.roleOrdering.roleIndexToRole(i));
      }
    }

    if (result.isTerminal)
    {
      // Add win bonus
      //  TODO - this needs adjustment to respect latched score ranges or else
      //  a best possible result that is a draw will not register as a decisively
      //  propagatable completion score.  This doesn't matter for fixed sum games
      //  that can only end with score of 0,50,100 or for puzzles, but would matter
      //  for more complex scoring structures in multi-player games (issue tracked in GITHub)
      for (int i = 0; i < tree.numRoles; i++)
      {
        double iScore = result.terminalScore[i];
        tree.bonusBuffer[i] = 0;

        for (int j = 0; j < tree.numRoles; j++)
        {
          if (j != i)
          {
            double jScore = result.terminalScore[j];

            if (iScore >= jScore)
            {
              double bonus = tree.gameCharacteristics.getCompetitivenessBonus();

              if (iScore > jScore)
              {
                bonus *= 2;
              }

              tree.bonusBuffer[i] += bonus;
            }
          }
        }
      }

      for (int i = 0; i < tree.numRoles; i++)
      {
        result.terminalScore[i] = ((result.terminalScore[i] + tree.bonusBuffer[i]) * 100) /
            (100 + 2 * (tree.numRoles - 1) *
                tree.gameCharacteristics.getCompetitivenessBonus());
      }
    }
    else
    {
      int nonNoopCount = 0;

      for (int i = 0; i < tree.numRoles && nonNoopCount < 2; i++ )
      {
        Role role = tree.roleOrdering.roleIndexToRole(i);
        ForwardDeadReckonLegalMoveSet moves = tree.underlyingStateMachine.getLegalMoveSet(theState);
        int numMoves = tree.searchFilter.getFilteredMovesSize(theState, moves, role, false);
        Iterator<ForwardDeadReckonLegalMoveInfo> itr = moves.getContents(role).iterator();
        for (int iMove = 0; iMove < numMoves; iMove++)
        {
          // Get next move for this factor
          ForwardDeadReckonLegalMoveInfo info = tree.searchFilter.nextFilteredMove(itr);

          if (info.inputProposition != null)
          {
            if (nonNoopCount++ > 0)
            {
              break;
            }
          }
        }
      }

      if (nonNoopCount == 1)
      {
        result.autoExpand = true;
      }
    }

    return result;
  }

  void createChildNodeForEdge(TreeEdge edge, ForwardDeadReckonLegalMoveInfo[] jointPartialMove)
  {
    boolean isPseudoNullMove = (tree.factor != null);
    int roleIndex = (decidingRoleIndex + 1) % tree.numRoles;

    for (int i = 0; i <= ((tree.removeNonDecisionNodes && mNumChildren > 1) ? tree.numRoles-1 : roleIndex); i++)
    {
      if (jointPartialMove[i].inputProposition != null)
      {
        isPseudoNullMove = false;
      }
    }

    assert(state != null);
    assert(edge.mChildRef == NULL_REF);

    ForwardDeadReckonInternalMachineState newState = null;
    if (roleIndex == tree.numRoles - 1 || (tree.removeNonDecisionNodes && mNumChildren > 1))
    {
      newState = tree.mNextStateBuffer;
      tree.underlyingStateMachine.getNextState(state, tree.factor, jointPartialMove, newState);

      //  In a factorized game we need to normalize the generated state
      //  so as to not fall foul of potential corruption of the non-factor
      //  element engendered by not making a move in other factors
      if ( tree.factor != null )
      {
        tree.makeFactorState(newState);
      }
    }
    TreeNode newChild = tree.allocateNode(newState, this, isPseudoNullMove);

    assert(!newChild.freed);
    edge.setChild(newChild);

    boolean isTransition = (newChild.depth != -1);
    boolean isUnexpanded = false;

    //  Don't overwrite the deciding role index if the child we got was actually a transposition into an already
    //  expanded node, as it could be some way down a forced response sequence
    if ( newChild.depth == -1 )
    {
      isUnexpanded = true;
      newChild.decidingRoleIndex = ((tree.removeNonDecisionNodes && mNumChildren > 1) ? tree.numRoles-1 : roleIndex);

      if (roleIndex != tree.numRoles - 1 && (!tree.removeNonDecisionNodes || mNumChildren == 1))
      {
        // assert(newState == null);
        newChild.autoExpand = autoExpand;
        newChild.setState(state);
      }
    }
//    else
//    {
//      if ( tree.firstChoiceNode == this )
//      {
//        tree.numUnexpandedRootChoices--;
//        LOGGER.info("Transpositioned on root choice " + edge.mPartialMove.move + " - count now: " + tree.numUnexpandedRootChoices);
//      }
//    }

    //  If this was a transposition to an existing node it can be linked at multiple depths.
    //  Give it the lowest depth at which it has been seen, as this is guaranteed to preserve
    //  correct implicit semantics of unexpanded children (assertion of non-terminality if below
    //  min game length depth)
    int expectedDepth;
    if ( tree.removeNonDecisionNodes && mNumChildren > 1 )
    {
      expectedDepth = ((depth/tree.numRoles + 1)*tree.numRoles + (newChild.decidingRoleIndex+1)%tree.numRoles);
    }
    else
    {
      expectedDepth = depth+1;
    }
    if ( newChild.depth < 0 || newChild.depth > expectedDepth )
    {
      newChild.depth = (short)expectedDepth;
    }

    //assert(!tree.removeNonDecisionNodes || this == tree.root || newChild.depth/tree.numRoles == depth/tree.numRoles + 1);
    //assert(newChild.depth/tree.numRoles >= depth/tree.numRoles && newChild.depth > depth);
    assert(newChild.depth%tree.numRoles == (newChild.decidingRoleIndex+1)%tree.numRoles);

//    if(newChild.depth <= depth)
//    {
//      tree.root.dumpTree("c:\\temp\\mctsTree.txt");
//    }

    //  If we transition into a complete node we need to have it re-process that
    //  completion again in the light of the new parentage
    if (newChild.complete)
    {
      tree.completedNodeQueue.add(newChild);
    }
  }

  private void considerPathToAsPlan()
  {
    assert(isTerminal);

    GamePlan plan = tree.mGameSearcher.getPlan();
    if ( plan != null )
    {
      List<ForwardDeadReckonLegalMoveInfo> fullPlayoutList = new LinkedList<>();

      //  Pick arbitrary path back to the root
      TreeNode current = this;

      while(current != tree.root)
      {
        TreeNode parent = current.parents.get(0);

        if ( current.decidingRoleIndex == 0 )
        {
          for(Object choice : parent.children)
          {
            if ( choice instanceof TreeEdge )
            {
              TreeEdge edge = (TreeEdge)choice;

              if ( edge.mChildRef != NULL_REF && get(edge.mChildRef) == current )
              {
                fullPlayoutList.add(0, edge.mPartialMove);
              }
            }
          }
        }

        current = parent;
      }

      plan.considerPlan(fullPlayoutList);
    }
  }

  public TreeNode expand(TreePath fullPathTo, ForwardDeadReckonLegalMoveInfo[] jointPartialMove, int parentDepth)
  {
    assert(this == tree.root || fullPathTo == null || parents.contains(fullPathTo.getTailElement().getParentNode()));

    assert(linkageValid());
//    if ( pathTo != null && tree.oldRoot != null && tree.firstChoiceNode != null )
//    {
//      if ( pathTo.getParentNode() == tree.firstChoiceNode )
//      {
//        System.out.println("!");
//      }
//    }

    TreeNode result = expandInternal(fullPathTo, jointPartialMove, parentDepth, false);

    assert(!tree.removeNonDecisionNodes || result.mNumChildren > 1 || result == tree.root || result.complete);

    //  Node trimming can only occur once all root choices have been 'reconnected' to the previous tree.
    //  Depending on whose turn it is this could be when all immediate children of the root have been
    //  expanded (our turn) or it could be that the immediate child of the root is a forced move for us,
    //  in which case it is when ITS children have all been expanded.  We record the node which represents
    //  the first choice (the root or its immediate child always) and how many children it has.  Any expansion
    //  of a child of that recorded node then decrements said count, and when it gets to zero we can trim
//    if ( pathTo != null && tree.oldRoot != null && tree.firstChoiceNode != null )
//    {
//      if ( pathTo.getParentNode() == tree.firstChoiceNode )
//      {
//        tree.numUnexpandedRootChoices--;
//        LOGGER.info("Expanding root choice " + pathTo.getEdge(true).mPartialMove.move + " - count now: " + tree.numUnexpandedRootChoices);
//      }
//
//      if ( tree.numUnexpandedRootChoices == 0 )
//      {
//        tree.oldRoot.freeAllBut(tree.root);
//        tree.oldRoot = null;
//        tree.firstChoiceNode = null;
//      }
//    }

    assert(result.linkageValid());
    return result;
  }

  private TreeNode expandInternal(TreePath fullPathTo, ForwardDeadReckonLegalMoveInfo[] jointPartialMove, int parentDepth, boolean isRecursiveExpansion)
  {
    TreePathElement pathTo = (fullPathTo == null ? null : fullPathTo.getTailElement());

    ProfileSection methodSection = ProfileSection.newInstance("TreeNode.expand");
    try
    {
      assert(this == tree.root || parents.size() > 0);
      assert(depth/tree.numRoles == tree.root.depth/tree.numRoles || tree.findTransposition(state) == this || (!tree.removeNonDecisionNodes && decidingRoleIndex != tree.numRoles-1));
      //assert(state.size()==10);
      //boolean assertTerminal = !state.toString().contains("b");
      //  Find the role this node is choosing for
      int roleIndex = (decidingRoleIndex + 1) % tree.numRoles;

      //  Don't bother evaluating terminality of children above the earliest completion depth
      boolean evaluateTerminalOnNodeCreation = (tree.evaluateTerminalOnNodeCreation && depth >= tree.gameCharacteristics.getEarliestCompletionDepth());

      //  Don't evaluate terminality on the root since it cannot be (and latched score states
      //  might indicate it should be treated as such, but this is never correct for the root)
      if (this != tree.root)
      {
        if ( roleIndex == 0 )
        {
          boolean parentEvaluatedTerminalOnNodeCreation = (tree.evaluateTerminalOnNodeCreation &&
                                                           !isRecursiveExpansion &&
                                                           parentDepth >= tree.gameCharacteristics.getEarliestCompletionDepth());
          if (!parentEvaluatedTerminalOnNodeCreation && mNumChildren == 0)
          {
            StateInfo info = calculateTerminalityAndAutoExpansion(state);

            isTerminal = info.isTerminal;
            autoExpand = info.autoExpand;

            if (isTerminal)
            {
              if ( tree.gameCharacteristics.isPseudoPuzzle )
              {
                tree.underlyingStateMachine.getLatchedScoreRange(tree.root.state, tree.roleOrdering.roleIndexToRole(0), tree.latchedScoreRangeBuffer);

                if ( info.terminalScore[0] == tree.latchedScoreRangeBuffer[1] )
                {
                  considerPathToAsPlan();
                }
              }
              markComplete(info.terminalScore, depth);
              return this;
            }
          }
        }
      }

      assert(!tree.searchFilter.isFilteredTerminal(state));
      assert(linkageValid());

      assert (mNumChildren == 0);
      {
        Role choosingRole = tree.roleOrdering.roleIndexToRole(roleIndex);
        int topMoveWeight = 0;

        if ( !isRecursiveExpansion && pathTo != null && pathTo.getEdge(true).getHasBeenTrimmed() )
        {
          //  If the node is unexpanded, yet has already been visited, this must
          //  be a re-expansion following trimming.
          //  Note - the first can visit occur without expansion as a random child
          //  of the last expanded node will be chosen to rollout from
          tree.numReExpansions++;
        }
        //validateAll();

        //LOGGER.debug("Expand our moves from state: " + state);
        ForwardDeadReckonLegalMoveSet moves = tree.underlyingStateMachine.getLegalMoveSet(state);
        mNumChildren = (short)tree.searchFilter.getFilteredMovesSize(state, moves, choosingRole, true);
        Iterator<ForwardDeadReckonLegalMoveInfo> itr;

        if ( mNumChildren == 1 && this != tree.root && tree.removeNonDecisionNodes )
        {
          assert(pathTo != null);

          TreeNode parent = pathTo.getParentNode();
          TreeEdge edge = pathTo.getEdge(false);

          assert(parent != null);
          assert(parent.linkageValid());
          assert(edge.mChildRef == getRef());

          itr = moves.getContents(choosingRole).iterator();

          //  Forced responses do not get their own nodes - we just re-purpose this one
          ForwardDeadReckonLegalMoveInfo forcedChoice = tree.searchFilter.nextFilteredMove(itr);
          ForwardDeadReckonInternalMachineState newState = tree.mChildStatesBuffer[0];
          TreeNode result = this;

          jointPartialMove[roleIndex] = forcedChoice;

          if (roleIndex == tree.numRoles - 1)
          {
            tree.setForcedMoveProps(state, jointPartialMove);
            newState = tree.mChildStatesBuffer[0];
            tree.underlyingStateMachine.getNextState(state,
                                                     tree.factor,
                                                     jointPartialMove,
                                                     newState);

            assert(!newState.equals(parent.state));
            //  In a factorized game we need to normalize the generated state
            //  so as to not fall foul of potential corruption of the non-factor
            //  element engendered by not making a move in other factors
            if ( tree.factor != null )
            {
              tree.makeFactorState(newState);
            }

            //  Have we transposed?
            TreeNode existing = tree.findTransposition(newState);
            if ( existing != null )
            {
              assert(existing != this);
              assert(existing != parent);
              assert(existing.state.equals(newState));
              assert(edge.mChildRef != existing.getRef());
              assert(existing.linkageValid());

              //  Detach the edge from the old node we just transitioned out of
              edge.mChildRef = NULL_REF;

              //  Need to check that we don't already have a different edge leading from the same parent to this newly transposed-to
              //  node (multiple forced move paths can have a common destination)
              if ( existing.parents.contains(parent))
              {
                short thisIndex = -1;
                short otherPathIndex = -1;

                for(short i = 0; i < parent.mNumChildren; i++)
                {
                  if ( parent.children[i] instanceof TreeEdge )
                  {
                    TreeEdge linkingEdge = (TreeEdge)parent.children[i];

                    if ( linkingEdge == edge )
                    {
                      thisIndex = i;
                    }
                    else if ( linkingEdge.mChildRef == existing.getRef() )
                    {
                      otherPathIndex = i;
                    }
                  }
                }

                assert(thisIndex != -1);
                assert(otherPathIndex != -1);

                //  This edge is being newly traversed (for the first time), but the
                //  other may already have been traversed multiple times, so we must retire
                //  this one in favour of the other
                parent.children[thisIndex] = edge.mPartialMove;
                if ( parent.primaryChoiceMapping == null )
                {
                  parent.primaryChoiceMapping = new short[parent.mNumChildren];

                  for(short i = 0; i < parent.mNumChildren; i++)
                  {
                    parent.primaryChoiceMapping[i] = i;
                  }
                }

                parent.primaryChoiceMapping[thisIndex] = otherPathIndex;

                tree.edgePool.free(edge);
                edge = (TreeEdge)parent.children[otherPathIndex];

                pathTo.set(parent, edge);
              }

              //  This situation can only occur on the first expansion of the child of the edge
              //  which happens the first or second time it is visited (first time it may result in
              //  a playout from a leaf node that doesn't get expanded).  If it turn out to be a
              //  transposition, not detected until the second selection, then the first selection
              //  will not register on the visit counting of the new child, and the edge count needs
              //  reducing to one for things to remain consistent
              //edge.setNumVisits(1);

              //  In any other path it's only valid to create a path element where the number
              //  of edge visits is no greater than the number of child node visits, but in the
              //  selectAction path where this recursion can replace a node due to transpositions
              //  we'll be in an intermediary state where the processing selection has incremented
              //  the edge count, but not yet the child node count (which will be incremented on the
              //  next step of the selection process).  This means the constraint can be temporarily
              //  'out by one', and rather than lose the power of these assertions we can make for the
              //  'normal' case we transiently increment the child node count here if and only if
              //  assertions are enabled.
              assert(existing.numVisits++ >= 0);
              edge.setChild(existing);
              pathTo.set(parent, edge);
              assert(existing.numVisits-- > 0);

              //  Strictly this new path from parent to child by a forced-move path might
              //  not be unique (it could turn out that multiple forced move sequences which
              //  have different starting moves lead to the same result)
              //  If it's NOT unique we must make it so
              if ( !existing.parents.contains(parent))
              {
                existing.addParent(parent);
              }

              assert(existing.linkageValid());
              assert(parent.linkageValid());

              //  If the node transposed to was complete it must have been a non-decisive completion
              //  but it could be that it completes all children of the new parent, so that must be
              //  checked
              if ( existing.complete )
              {
                parent.checkChildCompletion(false);
              }

              //  The original node is no longer needed for this path since we wound up transposing
              //  However, it may have several parents, and we can only trim it from the current
              //  selection path (selection through the other paths will later transpose to the same result)
              //  In rare cases this node may ALREADY have been freed by a decisive completion during
              //  a recursive expansion of a node transposed into during the recursion (that shared a common
              //  parent)
              if ( !freed )
              {
                mNumChildren = 0; //  Must reset this so it appears unexpanded for other paths if it doesn't get freed
                assert(parents.size() > 0);
                if ( parents.contains(parent) )
                {
                  freeFromAncestor(parent, null);
                }

                assert(freed || linkageValid());
                assert(parent.linkageValid());
              }

              //  If the transposed-to node is already expanded we're done
              if ( !existing.complete && existing.isUnexpanded() )
              {
                result = existing.expandInternal(fullPathTo, jointPartialMove, parentDepth, true);
                assert(result.mNumChildren > 1 || result == tree.root || result.complete);
                assert(result.linkageValid());
              }
              else
              {
                result = existing;
                assert(result.mNumChildren > 1 || result == tree.root || result.complete);
              }
              assert(result != this);
              assert(result != parent);
              assert(parent.complete || pathTo.getEdge(false).mChildRef == result.getRef());
            }
            else
            {
              //  Update the node transposition indexes
              //  Note - ideally we would NOT remove any indexing of the previous state
              //  since if it pointed to this node, then it is pointing at an interim
              //  state in a forced move sequence, which anyway should evaluate to
              //  the terminus of that sequence, which will be this node at the end of
              //  the recursion.  HOWEVER, the state is an object reference to a fixed collection
              //  associated with this node, and we're about to change its value, which will
              //  change its hash key, so we must either create a new internal state instance
              //  of remove the old index entry.  Due to the difficulty of cleaning up index
              //  entries that do not correspond to an extant node state we DO remove the old
              //  entry and accept that other forced moves paths transitioning through it will have to
              //  redo the sequence recursion.
              assert(tree.findTransposition(newState) == null);
              tree.removeFromTranspositionIndexes(this);
              state.copy(newState);
              tree.addToTranspositionIndexes(this);
            }
          }

          if ( result == this )
          {
            mNumChildren = 0;
            depth++;
            decidingRoleIndex = (decidingRoleIndex+1)%tree.numRoles;
            assert(depth%tree.numRoles == (decidingRoleIndex+1)%tree.numRoles);

            //  Recurse
            result = expandInternal(fullPathTo, jointPartialMove, parentDepth, true);
            assert(result.linkageValid());
          }

          assert(result.mNumChildren > 1 || result == tree.root || result.complete);
          return result;
        }

        // If the child array isn't large enough, expand it.
        assert(mNumChildren <= MCTSTree.MAX_SUPPORTED_BRANCHING_FACTOR);
        if (mNumChildren > children.length)
        {
          children = new Object[tree.gameCharacteristics.getChoicesHighWaterMark(mNumChildren)];
        }

        if (MCTSTree.USE_STATE_SIMILARITY_IN_EXPANSION)
        {
          if (mNumChildren > 1)
          {
            topMoveWeight = tree.mStateSimilarityMap.getTopMoves(state, jointPartialMove, tree.mNodeTopMoveCandidates);
          }
        }

        if ( tree.factor != null && tree.heuristic.isEnabled() && (tree.removeNonDecisionNodes || roleIndex == tree.numRoles - 1) )
        {
          //  If we're expanding the pseudo-noop in a factored game we need to correct for the case where
          //  it's ending a heuristic sequence
          if ( pathTo != null )
          {
            TreeEdge edge = pathTo.getEdge(false);

            if ( edge.mPartialMove.isPseudoNoOp )
            {
              assert(pathTo.getParentNode() == tree.root);

              //  Look to see if a sibling move has no heuristic variance - we can clone
              //  it's heuristic score and weight
              for(short index = 0; index < tree.root.mNumChildren; index++)
              {
                Object choice = tree.root.children[index];

                if ( choice != edge && choice instanceof TreeEdge )
                {
                  TreeEdge siblingEdge = (TreeEdge)choice;

                  if ( !siblingEdge.hasHeuristicDeviation )
                  {
                    TreeNode siblingNode = get(siblingEdge.mChildRef);

                    if ( siblingNode != null )
                    {
                      heuristicValue = siblingNode.heuristicValue;
                      heuristicWeight = siblingNode.heuristicWeight;
                      break;
                    }
                  }
                }
              }
            }
          }
        }

        //  If this is the first choice node discovered as we descend from the root not it and how many children it has
        tree.setForcedMoveProps(state, jointPartialMove);

        //  Must retrieve the iterator AFTER setting any forced move props, since it will also
        //  iterate over moves internally, and the legal move set iterator is a singleton
        itr = moves.getContents(choosingRole).iterator();

        boolean foundVirtualNoOp = false;
        for (short lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
        {
          ForwardDeadReckonLegalMoveInfo newChoice = tree.searchFilter.nextFilteredMove(itr);

          boolean isPseudoNullMove = (tree.factor != null && mNumChildren > 1);

          jointPartialMove[roleIndex] = newChoice;

          if ( isPseudoNullMove )
          {
            for (int i = 0; i <= roleIndex; i++)
            {
              if (jointPartialMove[i].inputProposition != null)
              {
                isPseudoNullMove = false;
              }
            }
          }

          ForwardDeadReckonInternalMachineState newState = tree.mChildStatesBuffer[lMoveIndex];
          if ((roleIndex == tree.numRoles - 1 || (mNumChildren != 1 && tree.removeNonDecisionNodes)) && (!foundVirtualNoOp || !newChoice.isVirtualNoOp))
          {
            newState = tree.mChildStatesBuffer[lMoveIndex];
            tree.underlyingStateMachine.getNextState(state,
                                                     tree.factor,
                                                     jointPartialMove,
                                                     newState);

            //  In a factorized game we need to normalize the generated state
            //  so as to not fall foul of potential corruption of the non-factor
            //  element engendered by not making a move in other factors
            if ( tree.factor != null )
            {
              tree.makeFactorState(newState);
            }
          }
          else
          {
            newState.copy(state);
          }

          if ( primaryChoiceMapping != null )
          {
            primaryChoiceMapping[lMoveIndex] = lMoveIndex;
          }

          //	Check for multiple moves that all transition to the same state
          //  Note - we use an approximation of the true game for search purposes
          //  wherein all virtual noops are considered to be the same and thus
          //  transition to the same state.  This is not strictly accurate, but is
          //  likely to be a very good approximation for any reasonable game.  The
          //  approximation would brak down in games where:
          //  1) There is a pool of virtual no-ops (aka moves that impact only parts of the
          //     base state space that are disconnected for our goals)
          //  2) An elective no-op can be beneficial
          //  3) The size of the pool of noops significantly depends on the particular
          //     choice made between them on ancestor nodes
          if (!isPseudoNullMove)
          {
            for (short i = 0; i < lMoveIndex; i++)
            {
              if (children[i] != null &&
                  ((foundVirtualNoOp && newChoice.isVirtualNoOp) ||
                   (roleIndex == tree.numRoles - 1 &&
                    tree.mChildStatesBuffer[i].equals(newState))))
              {
                if ( primaryChoiceMapping == null )
                {
                  primaryChoiceMapping = new short[mNumChildren];
                  for(short j = 0; j < lMoveIndex; j++)
                  {
                    primaryChoiceMapping[j] = j;
                  }
                }
                primaryChoiceMapping[lMoveIndex] = i;
                break;
              }
            }
          }

          assert(newChoice != null);
          children[lMoveIndex] = newChoice;

          foundVirtualNoOp |= newChoice.isVirtualNoOp;
        }

        assert(linkageValid());

        if (evaluateTerminalOnNodeCreation && (tree.removeNonDecisionNodes || roleIndex == tree.numRoles - 1))
        {
          for (short lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
          {
            if (primaryChoiceMapping == null || primaryChoiceMapping[lMoveIndex] == lMoveIndex)
            {
              StateInfo info = calculateTerminalityAndAutoExpansion(tree.mChildStatesBuffer[lMoveIndex]);

              if (info.isTerminal || info.autoExpand)
              {
                TreeEdge newEdge = tree.edgePool.allocate(tree.mTreeEdgeAllocator);
                newEdge.setParent(this, (ForwardDeadReckonLegalMoveInfo)children[lMoveIndex]);
                children[lMoveIndex] = newEdge;
                jointPartialMove[roleIndex] = newEdge.mPartialMove;
                createChildNodeForEdge(newEdge, jointPartialMove);

                TreeNode newChild = get(newEdge.mChildRef);
                newChild.isTerminal = info.isTerminal;
                newChild.autoExpand = info.autoExpand;
                if (info.isTerminal)
                {
                  if ( tree.gameCharacteristics.isPseudoPuzzle )
                  {
                    tree.underlyingStateMachine.getLatchedScoreRange(tree.root.state, tree.roleOrdering.roleIndexToRole(0), tree.latchedScoreRangeBuffer);

                    if ( info.terminalScore[0] == tree.latchedScoreRangeBuffer[1] )
                    {
                      newChild.considerPathToAsPlan();
                    }
                  }
                  newChild.markComplete(info.terminalScore, (short)(depth + 1));
                }
              }
            }
          }
        }

        assert(linkageValid());

        if (MCTSTRee.USE_STATE_SIMILARITY_IN_EXPANSION && topMoveWeight > 0)
        {
          for (short lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
          {
            if ((primaryChoiceMapping == null || primaryChoiceMapping[lMoveIndex] == lMoveIndex) )
            {
              Object choice = children[lMoveIndex];
              TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
              if (edge == null || !get(edge.mChildRef).isTerminal)
              {
                //  Skip this for pseudo-noops
                if ( (edge != null ? edge.mPartialMove : (ForwardDeadReckonLegalMoveInfo)choice).isPseudoNoOp )
                {
                  continue;
                }

                for (int i = 0; i < tree.mNodeTopMoveCandidates.length; i++)
                {
                  ForwardDeadReckonLegalMoveInfo moveCandidate = tree.mNodeTopMoveCandidates[i];
                  if (choice == moveCandidate || (edge != null && edge.mPartialMove == moveCandidate))
                  {
                    if (edge == null)
                    {
                      edge = tree.edgePool.allocate(tree.mTreeEdgeAllocator);
                      edge.setParent(this, moveCandidate);
                      children[lMoveIndex] = edge;
                    }
                    edge.explorationAmplifier = (topMoveWeight * (tree.mNodeTopMoveCandidates.length + 1 - i)*2) /
                                                (tree.mNodeTopMoveCandidates.length + 1);
                    break;
                  }
                }
              }
            }
          }
        }

        assert(linkageValid());

        if ( tree.heuristic.isEnabled() && (tree.removeNonDecisionNodes || roleIndex == tree.numRoles - 1) )
        {
          // Determine the appropriate reference node to evaluate children with respect to
          // Evaluate wrt the first ancestor state with a reasonable number of visits which
          // is not itself immediately preceded by a heuristic exchange
          boolean previousEdgeHadHeuristicDeviation = false;
          TreeNode referenceNode = tree.root;

          if ( fullPathTo != null )
          {
            boolean previousEdgeWalked = false;
            TreePathElement pathElement = null;

            while(fullPathTo.hasMore())
            {
              fullPathTo.getNextNode();
              pathElement = fullPathTo.getCurrentElement();

              assert(pathElement != null);

              boolean pathElementHasHeuristicDeviation = pathElement.getEdge(false).hasHeuristicDeviation;

              if ( !previousEdgeWalked )
              {
                previousEdgeHadHeuristicDeviation = pathElementHasHeuristicDeviation;
                previousEdgeWalked = true;
              }

              if ( pathElement.getParentNode().numUpdates > 200 && !pathElementHasHeuristicDeviation )
              {
                break;
              }
            }

            //  Restore path cursor so that next attempt to enumerate is clean
            fullPathTo.resetCursor();

            if ( pathElement != null )
            {
              referenceNode = pathElement.getParentNode();
            }
          }

          short firstIndexWithHeuristic = -1;

          for (short lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
          {
            if ((primaryChoiceMapping == null || primaryChoiceMapping[lMoveIndex] == lMoveIndex) )
            {
              Object choice = children[lMoveIndex];

              //  Skip this for pseudo-noops since we don't want to expand them except when they are immediate
              //  children of the root (and in that case their heuristic value is the same as the root's)
              if ( ((choice instanceof TreeEdge) ? ((TreeEdge)choice).mPartialMove : (ForwardDeadReckonLegalMoveInfo)choice).isPseudoNoOp )
              {
                continue;
              }

              // Determine the heuristic value for this child.
              tree.heuristic.getHeuristicValue( tree.mChildStatesBuffer[lMoveIndex],
                                                state,
                                                referenceNode.state,
                                                tree.mNodeHeuristicInfo);

              assert(checkFixedSum(tree.mNodeHeuristicInfo.heuristicValue));

              TreeEdge edge = null;

              if ( tree.mNodeHeuristicInfo.treatAsSequenceStep )
              {
                if ( children[lMoveIndex] instanceof TreeEdge )
                {
                  edge = (TreeEdge)children[lMoveIndex];
                }
                else
                {
                  edge = tree.edgePool.allocate(tree.mTreeEdgeAllocator);
                  edge.setParent(this, (ForwardDeadReckonLegalMoveInfo)children[lMoveIndex]);
                  children[lMoveIndex] = edge;
                }

                assert(edge!=null);

                edge.hasHeuristicDeviation = true;
              }

              if (tree.mNodeHeuristicInfo.heuristicWeight > 0)
              {
                boolean applyHeuristicHere = (firstIndexWithHeuristic != -1);
                double heuristicWeightToApply = 0;

                if ( tree.mNodeHeuristicInfo.treatAsSequenceStep || (pathTo != null && !previousEdgeHadHeuristicDeviation) )
                {
                  applyHeuristicHere |= tree.mNodeHeuristicInfo.treatAsSequenceStep;
                }
                else if ( pathTo != null )
                {
                  heuristicWeightToApply = tree.mNodeHeuristicInfo.heuristicWeight;

                  applyHeuristicHere = true;
                }
                if ( applyHeuristicHere )
                {
                  if ( firstIndexWithHeuristic == -1 )
                  {
                    firstIndexWithHeuristic = lMoveIndex;
                    if ( lMoveIndex > 0 )
                    {
                      lMoveIndex = -1;
                      continue;
                    }
                  }
                  //  Create the edge if necessary
                  if ( edge == null )
                  {
                    if ( children[lMoveIndex] instanceof TreeEdge )
                    {
                      edge = (TreeEdge)children[lMoveIndex];
                    }
                    else
                    {
                      edge = tree.edgePool.allocate(tree.mTreeEdgeAllocator);
                      edge.setParent(this, (ForwardDeadReckonLegalMoveInfo)children[lMoveIndex]);
                      children[lMoveIndex] = edge;
                    }
                  }

                  assert(edge != null);

                  jointPartialMove[roleIndex] = edge.mPartialMove;

                  assert(linkageValid());

                  if ( edge.mChildRef == NULL_REF )
                  {
                    createChildNodeForEdge(edge, jointPartialMove);

                    assert(linkageValid());

                    assert(!evaluateTerminalOnNodeCreation || !calculateTerminalityAndAutoExpansion(get(edge.mChildRef).state).isTerminal);

                    assert(linkageValid());
                  }

                  TreeNode newChild = get(edge.mChildRef);

                  //  If this turns out to be a transition into an already visited child
                  //  then do not apply the heuristics
                  if (newChild.numVisits == 0 && !newChild.isTerminal)
                  {
                    newChild.heuristicValue = tree.mNodeHeuristicInfo.heuristicValue[0];
                    newChild.heuristicWeight = heuristicWeightToApply;

                    for (int i = 0; i < tree.numRoles; i++)
                    {
                      double adjustedRoleScore;
                      double referenceRoleScore = referenceNode.getAverageScore(i);

                      //  Weight by a measure of confidence in the reference score
                      //  TODO - experiment - should this be proportional to sqrt(num root visits)?
                      double referenceScoreWeight = referenceNode.numUpdates/50;
                      referenceRoleScore = (referenceRoleScore*referenceScoreWeight + 50)/(referenceScoreWeight+1);

                      if (tree.mNodeHeuristicInfo.heuristicValue[i] > 50)
                      {
                        adjustedRoleScore = referenceRoleScore +
                                              (100 - referenceRoleScore) *
                                              (tree.mNodeHeuristicInfo.heuristicValue[i] - 50) /
                                              50;
                      }
                      else
                      {
                        adjustedRoleScore = referenceRoleScore -
                                              (referenceRoleScore) *
                                              (50 - tree.mNodeHeuristicInfo.heuristicValue[i]) /
                                              50;
                      }

                      double newChildRoleScore = (newChild.getAverageScore(i)*newChild.numUpdates + adjustedRoleScore*tree.mNodeHeuristicInfo.heuristicWeight)/(newChild.numUpdates+tree.mNodeHeuristicInfo.heuristicWeight);
                      newChild.setAverageScore(i, newChildRoleScore);
                    }

                    // Use the heuristic confidence to guide how many virtual rollouts to pretend there have been through
                    // the new child.
                    newChild.numUpdates += tree.mNodeHeuristicInfo.heuristicWeight;
                    assert(!Double.isNaN(newChild.getAverageScore(0)));

                    newChild.numVisits += tree.mNodeHeuristicInfo.heuristicWeight;
                  }
                }
              }
            }
          }
        }

        assert(linkageValid());

        //validateAll();

        if (evaluateTerminalOnNodeCreation && (tree.removeNonDecisionNodes || roleIndex == tree.numRoles - 1) )
        {
          boolean completeChildFound = false;
          TreeNode decisiveCompletionNode = null;

          for (int lii = 0; lii < mNumChildren; lii++)
          {
            Object choice = children[lii];
            if (choice instanceof TreeEdge)
            {
              long cr = ((TreeEdge)choice).mChildRef;
              if (cr != NULL_REF && get(cr) != null)
              {
                TreeNode lNode = get(cr);
                if (lNode.isTerminal)
                {
                  //lNode.markComplete(lNode, lNode.depth);
                  lNode.complete = true;
                  lNode.completionDepth = lNode.depth;

                  if ( !completeChildFound )
                  {
                    completeChildFound = true;
                    tree.underlyingStateMachine.getLatchedScoreRange(state, tree.roleOrdering.roleIndexToRole(roleIndex), tree.latchedScoreRangeBuffer);
                  }

                  if ( tree.latchedScoreRangeBuffer[0] != tree.latchedScoreRangeBuffer[1] && lNode.getAverageScore(roleIndex) > tree.latchedScoreRangeBuffer[1] - EPSILON)
                  {
                    decisiveCompletionNode = lNode;
                  }
                }
                if (lNode.complete)
                {
                  completeChildFound = true;
                }
              }
            }
          }

          if (completeChildFound && !complete)
          {
            if ( decisiveCompletionNode != null )
            {
              decisiveCompletionNode.processCompletion();
            }
            else
            {
              checkChildCompletion(true);
            }
          }
        }
      }

      assert(linkageValid());

      return this;
    }
    finally
    {
      methodSection.exitScope();
    }
  }

  private void validateScoreVector(double[] scores)
  {
    double total = 0;

    for (int i = 0; i < tree.numRoles; i++)
    {
      total += scores[i];
    }

    if (total > 0 && Math.abs(total - 100) > EPSILON)
    {
      LOGGER.warn("Bad score vector");
    }

    if (total > 0 && mNumChildren != 0)
    {
      total = 0;
      int visitTotal = 0;

      for (short index = 0; index < mNumChildren; index++)
      {
        if ( primaryChoiceMapping == null || primaryChoiceMapping[index] == index )
        {
          Object choice = children[index];

          TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
          if (edge != null && edge.mChildRef != NULL_REF)
          {
            total += get(edge.mChildRef).getAverageScore(0) * edge.getNumChildVisits();
            visitTotal += edge.getNumChildVisits();
          }
        }
      }

      if (visitTotal > 200 &&
          Math.abs(getAverageScore(0) - total / visitTotal) > 10)
      {
        LOGGER.warn("Parent stats do not match children");
      }
    }
  }

  private double explorationUCT(int effectiveTotalVisits,
                                TreeEdge edge,
                                int roleIndex)
  {
    // Extract the common parts of the calculation to avoid making expensive calls twice.
    double lVarianceExploration;
    double lUcbExploration;
    TreeNode childNode = get(edge.mChildRef);
    int effectiveNumChildVisits = edge.getNumChildVisits() + 1;

    int lNumChildVisits = edge.getNumChildVisits();

    //  If we're using weight decay we need to normalize the apparent sample sizes
    //  used to calculate the upper bound on variance for UCB-tuned or else the
    //  calculated upper bound on variance will be too low (we gain less information
    //  from a diluted weight playout than from a less diluted one).  Empirically this
    //  treatment produces far better results and allows UCB-tuned to continue to be used
    //  in conjunction with weight decay.
    //  Note - an analogous treatment of the sample sizes used to compute the simple UCB
    //  element is not helpful and actually does considerable harm in at last some games
    if (tree.mWeightDecayKneeDepth > 0)
    {
      double normalizedNumVisits;
      double normalizedNumChildVisits;

      if ( childNode.numUpdates > 0 )
      {
        normalizedNumVisits = effectiveTotalVisits*(numUpdates+1)/numVisits;
        normalizedNumChildVisits = effectiveNumChildVisits*(childNode.numUpdates+1)/childNode.numVisits;
      }
      else
      {
        normalizedNumVisits = effectiveTotalVisits;
        normalizedNumChildVisits = effectiveNumChildVisits;
      }

      lVarianceExploration = 2 * Math.log(Math.max(normalizedNumVisits, normalizedNumChildVisits)) / normalizedNumChildVisits;
      lUcbExploration = 2 * Math.log(Math.max(effectiveTotalVisits, effectiveNumChildVisits)) / effectiveNumChildVisits;
    }
    else
    {
      lUcbExploration = 2 * Math.log(Math.max(effectiveTotalVisits, effectiveNumChildVisits)) / effectiveNumChildVisits;
      lVarianceExploration = lUcbExploration;
    }

    double result;

    if (MCTSTree.USE_UCB_TUNED)
    {
      // When we propagate adjustments due to completion we do not also adjust the variance contribution so this can
      // result in 'impossibly' low (aka negative) variance - take a lower bound of 0
      double roleAverageScore = getAverageScore(roleIndex);
      double roleAverageSquaredScore = getAverageSquaredScore(roleIndex);

      double varianceBound = Math.max(0, roleAverageSquaredScore -
                                    roleAverageScore *
                                    roleAverageScore) /
                                    10000 +
                                    Math.sqrt(lVarianceExploration);
      result = tree.gameCharacteristics.getExplorationBias() *
          Math.sqrt(Math.min(0.25, varianceBound) * lUcbExploration) / tree.roleRationality[roleIndex];
    }
    else
    {
      result = tree.gameCharacteristics.getExplorationBias() *
          Math.sqrt(lUcbExploration) / tree.roleRationality[roleIndex];
    }

    result *= (1 + edge.explorationAmplifier);
    return result;
  }

  private double getAverageCousinMoveValue(TreeEdge relativeTo, int roleIndex)
  {
    TreeNode lNode = get(relativeTo.mChildRef);
    if (lNode.decidingRoleIndex == 0)
    {
      return lNode.getAverageScore(roleIndex);
    }
    else if (tree.cousinMovesCachedFor == NULL_REF || get(tree.cousinMovesCachedFor) != this)
    {
      tree.cousinMovesCachedFor = getRef();
      tree.cousinMoveCache.clear();
      tree.mCachedMoveScorePool.clear(tree.mMoveScoreInfoAllocator, false);

      for (TreeNode parent : parents)
      {
        for (short index = 0; index < parent.mNumChildren; index++)
        {
          if ( parent.primaryChoiceMapping == null || parent.primaryChoiceMapping[index] == index )
          {
            Object choice = parent.children[index];

            TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
            if (edge == null || edge.mChildRef == NULL_REF)
            {
              continue;
            }

            TreeNode child = get(edge.mChildRef);
            if (child != null)
            {
              for (short nephewIndex = 0; nephewIndex < child.mNumChildren; nephewIndex++)
              {
                Object rawChoice = child.children[nephewIndex];
                Object nephewChoice = child.children[child.primaryChoiceMapping == null ? nephewIndex : child.primaryChoiceMapping[nephewIndex]];

                TreeEdge nephewEdge = (nephewChoice instanceof TreeEdge ? (TreeEdge)nephewChoice : null);
                if (nephewEdge == null || nephewEdge.mChildRef == NULL_REF)
                {
                  continue;
                }

                TreeNode nephew = get(nephewEdge.mChildRef);
                if (nephew != null && (nephew.numUpdates > 0 || nephew.complete))
                {
                  Move move = (rawChoice instanceof TreeEdge ? nephewEdge.mPartialMove : (ForwardDeadReckonLegalMoveInfo)rawChoice).move;
                  MoveScoreInfo accumulatedMoveInfo = tree.cousinMoveCache.get(move);
                  if (accumulatedMoveInfo == null)
                  {
                    accumulatedMoveInfo = tree.mCachedMoveScorePool.allocate(tree.mMoveScoreInfoAllocator);
                    tree.cousinMoveCache.put(move, accumulatedMoveInfo);
                  }

                  for(int i = 0; i < tree.numRoles; i++)
                  {
                    accumulatedMoveInfo.averageScores[i] = (accumulatedMoveInfo.averageScores[i] *
                        accumulatedMoveInfo.numSamples + nephew.getAverageScore(i)) /
                        (accumulatedMoveInfo.numSamples + 1);
                  }
                  accumulatedMoveInfo.numSamples++;

                  assert(checkFixedSum(accumulatedMoveInfo.averageScores));
                }
              }
            }
          }
        }
      }
    }

    MoveScoreInfo accumulatedMoveInfo = tree.cousinMoveCache.get(relativeTo.mPartialMove.move);
    if (accumulatedMoveInfo == null)
    {
      if ( lNode.numUpdates > 0 )
      {
        parents.get(0).dumpTree("c:\\temp\\subTree.txt");
        LOGGER.warn("No newphews found for search move including own child!");
        tree.cousinMovesCachedFor = NULL_REF;
      }
      //getAverageCousinMoveValue(relativeTo);
      return lNode.getAverageScore(roleIndex);
    }
    return accumulatedMoveInfo.averageScores[roleIndex];
  }

  private double exploitationUCT(TreeEdge inboundEdge, int roleIndex)
  {
    //  Force selection of a pseudo-noop as an immediate child of the
    //  root aas much as the best scoring node as there is a 50-50 chance we'll need to pass
    //  on this factor (well strictly (#factors-1)/#factors but 1:1 is good
    //  enough), so we need good estimates on the score for the pseudo-noop
    if (inboundEdge.mPartialMove.isPseudoNoOp && this == tree.root)
    {
      double bestChildScore = 0;

      for (short index = 0; index < mNumChildren; index++)
      {
        if ( primaryChoiceMapping == null || primaryChoiceMapping[index] == index )
        {
          Object choice = children[index];

          TreeEdge edge2 = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
          if (edge2 != null && edge2.mChildRef != NULL_REF)
          {
            TreeNode lChild = get(edge2.mChildRef);
            if (lChild.getAverageScore(roleIndex) > bestChildScore)
            {
              bestChildScore = lChild.getAverageScore(roleIndex);
            }
          }
        }
      }

      return bestChildScore / 100;
    }

    TreeNode lInboundChild = get(inboundEdge.mChildRef);
    if (tree.gameCharacteristics.isSimultaneousMove)
    {
      if (roleIndex == 0)
      {
        return lInboundChild.getAverageScore(roleIndex) / 100;
      }
      return getAverageCousinMoveValue(inboundEdge, roleIndex)/100;
    }

    double result = lInboundChild.getAverageScore(roleIndex) / 100;// + heuristicValue()/Math.log(numVisits+2);// + averageSquaredScore/20000;

//    if (lInboundChild.heuristicValue != 0)
//    {
//      double applicableValue = (lInboundChild.heuristicValue > 50 ? lInboundChild.heuristicValue : 100 - lInboundChild.heuristicValue);
//      double heuristicAdjustedValue;
//
//      if ((heuristicValue > 50) == (roleIndex == 0))
//      {
//        heuristicAdjustedValue = result + (1 - result) * (applicableValue - 50) / 50;
//      }
//      else
//      {
//        heuristicAdjustedValue = result - (result) * (applicableValue - 50) / 50;
//      }
//
//      result = (result*lInboundChild.numUpdates + 50*heuristicAdjustedValue)/(lInboundChild.numUpdates+50);
//    }

    return result;
  }

  //  UCT value to use for an unexpanded child during select on its parent
  //  Basic MCTS would use a large value, guaranteeing that unexplored nodes
  //  always receive one visit before anything receives two.  However this can lead
  //  to very slow convergence in large branching factor games where there turns out to be
  //  a stand-out choice.  Instead we use the value that would be generated by a single
  //  visit to a child whose value was the same as that of the parent - i.e. - we start from
  //  the assumption that the best estimate of a child node's value without any play-throughs
  //  is the same as that of its parent
  private double unexpandedChildUCTValue(int roleIndex, double explorationAmplifier)
  {
    if ( tree.useEstimatedValueForUnplayedNodes )
    {
      // Extract the common parts of the calculation to avoid making expensive calls twice.
      double lCommon = 2 * Math.log(numVisits + 1);

      double varianceBound = Math.sqrt(lCommon);
      double explorationTerm = tree.gameCharacteristics.getExplorationBias() *
             Math.sqrt(Math.min(0.5, varianceBound) * lCommon) *
             (1+explorationAmplifier);

      return explorationTerm + getAverageScore(roleIndex)/100 + tree.r.nextDouble() * EPSILON;
    }

    //  Else use standard MCTS very high values for unplayed
    return 1000 + tree.r.nextDouble() * EPSILON + explorationAmplifier;
  }

  private void normalizeScores()
  {
    double weightTotal = 0;

    for(int role = 0; role < tree.numRoles; role++)
    {
      tree.mNodeAverageScores[role] = 0;
      tree.mNodeAverageSquaredScores[role] = 0;
    }

    for(int i = 0; i < mNumChildren; i++)
    {
      Object choice = children[i];

      if ( choice instanceof TreeEdge )
      {
        TreeEdge edge = (TreeEdge)choice;

        if ( edge.mChildRef != NULL_REF )
        {
          TreeNode child = get(edge.mChildRef);

          if ( child != null  )
          {
            double weight = edge.getNumChildVisits()*Math.sqrt(edge.getNumChildVisits());

            for(int role = 0; role < tree.numRoles; role++)
            {
              tree.mNodeAverageScores[role] += weight*child.getAverageScore(role);
              tree.mNodeAverageSquaredScores[role] += weight*child.getAverageSquaredScore(role);
            }
            weightTotal += weight;
          }
        }
      }
    }

    if ( weightTotal > 0)
    {
      for(int role = 0; role < tree.numRoles; role++)
      {
        setAverageScore(role, tree.mNodeAverageScores[role]/weightTotal);
        setAverageSquaredScore(role, tree.mNodeAverageSquaredScores[role]/weightTotal);
      }
    }
  }

  /**
   * Select the child node to descend through during MCTS node selection.
   *
   * @param path - path taken from the root to this node
   * @param jointPartialMove - partial current move at this depth
   * @param xiForceMove - force selection of the specified move, provided that it's our turn (or null not to force).
   *
   * @return PathElement to add to the path representing the selected child
   */
  TreePathElement select(TreePath path, ForwardDeadReckonLegalMoveInfo[] jointPartialMove, Move xiForceMove)
  {
    TreeEdge selected = null;
    int selectedIndex = -1;
    int bestSelectedIndex = -1;
    double bestCompleteValue = Double.MIN_VALUE;
    TreeNode bestCompleteNode = null;
    double bestValue = Double.MIN_VALUE;

    //	Find the role this node is choosing for
    int roleIndex = (decidingRoleIndex + 1) % tree.numRoles;

    tree.cousinMovesCachedFor = NULL_REF;
    //LOGGER.debug("Select in " + state);
    assert (mNumChildren != 0);
    {
      tree.mGameSearcher.mAverageBranchingFactor.addSample(mNumChildren);

      //  If there is only one choice we have to select it
      if (mNumChildren == 1)
      {
        //  Non-simultaneous move games always collapse one-choice nodes
        assert(!tree.removeNonDecisionNodes || this == tree.root);
        selectedIndex = 0;
      }
      else if ((xiForceMove != null) && (roleIndex == 0))
      {
        // Find the move that we already know we're going to play.
        for (short lii = 0; lii < mNumChildren; lii++)
        {
          if (children[lii] instanceof TreeEdge)
          {
            TreeEdge lEdge = (TreeEdge)children[lii];
            if (lEdge.mPartialMove.move.equals(xiForceMove))
            {
              selectedIndex = lii;
            }
          }
        }

        assert(selectedIndex != -1) : "Failed to find forced move: " + xiForceMove;
      }
      else
      {
        if ( tree.USE_NODE_SCORE_NORMALIZATION && numVisits > 500 && (numVisits&0xff) == 0xff )
        {
          normalizeScores();
        }
        //  It is clearly a bug to reset mostLikelyRunnerUpValue here, but empirically it is significantly
        //  useful in some games (D&B, D&B suicide notably).  The only games where a clear negative impact
        //  has been observed is the Breakthrough family.  Hypothetically this is probably because the
        //  repeated selection it results in amplifies heuristic-induced distortions.
        //  FOR NOW (and it is a priority to understand this better and replace with a more
        //  controlled mechanism) we perform the 'buggy' reset for non-heuristic games
        if ( !tree.heuristic.isEnabled() )
        {
          mostLikelyRunnerUpValue = Double.MIN_VALUE;
        }
        //  We cache the best and second best selections so that on future selects through
        //  the node we only have to check that the best has not fallen in value below the
        //  second best, and do a full rescan only if it has (a few operations also clear the cached
        //  value, such as completion processing)
        if (mostLikelyWinner != -1 && (tree.factor == null || this != tree.root))
        {
          Object choice = children[mostLikelyWinner];
          TreeEdge edge;

          if ( choice instanceof TreeEdge )
          {
            edge = (TreeEdge)choice;
          }
          else
          {
            edge = tree.edgePool.allocate(tree.mTreeEdgeAllocator);
            edge.setParent(this, (ForwardDeadReckonLegalMoveInfo)choice);
            children[mostLikelyWinner] = edge;
          }
          long cr = edge.mChildRef;

          if(cr != NULL_REF)
          {
            TreeNode c = get(cr);
            if (c != null && (!c.complete) && !c.allChildrenComplete)
            {
              double uctValue;

              if (edge.getNumChildVisits() == 0 && !c.complete)
              {
                uctValue = unexpandedChildUCTValue(roleIndex, edge.explorationAmplifier);
              }
              else
              {
                //  Various experiments have been done to try to find the best selection
                //  weighting, and it seems that using the number of times we've visited the
                //  child FROM THIS PARENT coupled with the num visits here in standard UCT
                //  manner works best.  In particular using the visit count on the child node
                //  (rather than through the child edge to it, which can be radically different
                //  in highly transpositional games) does not seem to work as well (even with a
                //  'corrected' parent visit count obtained by summing the number of visits to all
                //  the child's parents)
                uctValue = explorationUCT(numVisits, edge, roleIndex) +
                           exploitationUCT(edge, roleIndex);
              }

              if (uctValue >= mostLikelyRunnerUpValue)
              {
                selectedIndex = mostLikelyWinner;
              }
            }
          }
        }

        if (selectedIndex == -1)
        {
          //  Previous second best now preferred over previous best so we need
          //  to recalculate
          mostLikelyRunnerUpValue = Double.MIN_VALUE;

          for (short i = 0; i < mNumChildren; i++)
          {
            //  Only select one move that is state-equivalent, and don't allow selection of a pseudo-noop
            if ( primaryChoiceMapping == null || primaryChoiceMapping[i] == i )
            {
              Object choice = children[i];

              TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
              double uctValue;
              long cr;
              TreeNode c;

              if (edge != null && (cr = edge.mChildRef) != NULL_REF && (c = get(cr)) != null)
              {
                //  Don't allow selection of a pseudo-noop
                //  except from the root since we just want to know the difference in cost or omitting one
                //  move (at root level) if we play in another factor
                if ((!c.complete || (tree.allowAllGamesToSelectThroughComplete || tree.gameCharacteristics.isSimultaneousMove || tree.gameCharacteristics.numRoles > 2)) &&
                         (tree.root == this || !edge.mPartialMove.isPseudoNoOp))
                {
                  //  Don't preferentially explore paths once they are known to have complete results
                  if ( c.complete )
                  {
                    edge.explorationAmplifier = 0;
                  }

                  if (edge.getNumChildVisits() == 0)
                  {
                    uctValue = unexpandedChildUCTValue(roleIndex, edge.explorationAmplifier);
                  }
                  else
                  {
                    assert(edge.getNumChildVisits() <= c.numVisits);

                    //  Various experiments have been done to try to find the best selection
                    //  weighting, and it seems that using the number of times we've visited the
                    //  child FROM THIS PARENT coupled with the num visits here in standard UCT
                    //  manner works best.  In particular using the visit count on the child node
                    //  (rather than through the child edge to it, which can be radically different
                    //  in highly transpositional games) does not seem to work as well (even with a
                    //  'corrected' parent visit count obtained by summing the number of visits to all
                    //  the child's parents)
                    //  Empirically the half value for the exploration term applied to complete
                    //  children seems to give decent results.  Both applying it in full and not
                    //  applying it (both of which can be rationalized!) seem to fare worse in at
                    //  least some games
                    uctValue = (c.complete ? explorationUCT(numVisits,
                                                            edge,
                                                            roleIndex)/2
                                           : explorationUCT(numVisits,
                                                            edge,
                                                            roleIndex)) +
                               exploitationUCT(edge, roleIndex);
                  }

                  //  If the node we most want to select through is complete (or all its
                  //  children are, in either case of which there is nothing further to
                  //  learn) we select the best non-compleet choice but record the fact
                  //  so that on propagation of the result we can propagate upwards from this
                  //  node the score of the complete node that in some sense 'should' have
                  //  been selected
                  if (!c.complete && !c.allChildrenComplete)
                  {
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
                  else
                  {
                    if (uctValue > bestCompleteValue)
                    {
                      bestCompleteValue = uctValue;
                      bestCompleteNode = c;
                      bestSelectedIndex = i;
                    }
                  }
                }
              }
              else if ( tree.root == this || !(edge == null ? (ForwardDeadReckonLegalMoveInfo)choice : edge.mPartialMove).isPseudoNoOp )
              {
                //  A null child ref in an extant edge is a not-yet selected through
                //  path which is asserted to be non-terminal and unvisited
                uctValue = unexpandedChildUCTValue(roleIndex, edge == null ? 0 : edge.explorationAmplifier);

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

    if ( selectedIndex == -1 )
    {
      //  This can happen only if all children are complete.  If we are explicitly allowing
      //  selection through complete nodes then the best one will have been determined and
      //  we should use it.  If this is not the case then what has happened is that a child
      //  has been completed by an expansion, and when completions are processed this node will
      //  also be completed, but that has not happened yet.  In that case a random selection
      //  will be fine as this node is already actually fully determined
      if(bestSelectedIndex != -1)
      {
        selectedIndex = bestSelectedIndex;
        bestValue = bestCompleteValue;
      }
      else
      {
        TreeEdge chosenEdge;

        do
        {
          selectedIndex = tree.r.nextInt(mNumChildren);
          if ( primaryChoiceMapping != null )
          {
            selectedIndex = primaryChoiceMapping[selectedIndex];
          }
          assert(children[selectedIndex] instanceof TreeEdge);
          chosenEdge = (TreeEdge)children[selectedIndex];
        } while(tree.root != this && chosenEdge.mPartialMove.isPseudoNoOp);

        assert(get(chosenEdge.mChildRef) != null);
        assert(chosenEdge.mPartialMove.isPseudoNoOp || get(chosenEdge.mChildRef).complete);
      }
    }
    assert(selectedIndex != -1);

    mostLikelyWinner = (short)selectedIndex;

    //  Expand the edge if necessary
    Object choice = children[selectedIndex];

    if ( choice instanceof TreeEdge )
    {
      selected = (TreeEdge)choice;
    }
    else
    {
      selected = tree.edgePool.allocate(tree.mTreeEdgeAllocator);
      selected.setParent(this, (ForwardDeadReckonLegalMoveInfo)choice);
      children[selectedIndex] = selected;
    }

    jointPartialMove[roleIndex] = selected.mPartialMove;

    if (selected.mChildRef == NULL_REF)
    {
      //  If this node does not follow its parent in joint-move order (because interim forced-choices have
      //  been trimmed out) then the joint move array will not necessarily contain the correct
      //  information and must be calculated a priori.  For now we have no means to distinguish this
      //  case so we always do so.  This is not expensive because the following creation of the child
      //  node anyway has top call getNextStat() and thus run the state through the state machine,
      //  which is the bulk of the cost
      tree.setForcedMoveProps(state, jointPartialMove);

      createChildNodeForEdge(selected, jointPartialMove);

      assert(!tree.evaluateTerminalOnNodeCreation ||
             depth < tree.gameCharacteristics.getEarliestCompletionDepth() ||
             this == tree.root ||
             !calculateTerminalityAndAutoExpansion(get(selected.mChildRef).state).isTerminal);
    }

    assert(get(selected.mChildRef) != null);

    if (!complete && (tree.removeNonDecisionNodes || roleIndex == 0) && MCTSTree.USE_STATE_SIMILARITY_IN_EXPANSION )
    {
      tree.mStateSimilarityMap.add(this);
    }

    final double explorationAmplifierDecayRate = 0.6;
    selected.explorationAmplifier *= explorationAmplifierDecayRate;
    TreePathElement result = path.push(this, selected);

    //  If the node that should have been selected through was complete
    //  note that in the path, so that on application of the update
    //  the propagation upward from this node can be corrected
    //  HACK - we disable this, at least for now, in puzzles because of MaxKnights
    //  which happens to do well from the distorted stats you get without it.  This
    //  is due to the particular circumstance in MaxKnights that scores can only
    //  go up!
    if ((tree.allowAllGamesToSelectThroughComplete || tree.gameCharacteristics.isSimultaneousMove || tree.gameCharacteristics.numRoles > 2) &&
        bestCompleteNode != null && bestCompleteValue > bestValue && tree.gameCharacteristics.numRoles != 1)
    {
      assert(children[bestSelectedIndex] instanceof TreeEdge);
      TreeEdge bestSelectedEdge = (TreeEdge)children[bestSelectedIndex];
      assert(bestCompleteNode == get(bestSelectedEdge.mChildRef));

      result.setScoreOverrides(bestCompleteNode);
      bestCompleteNode.numVisits++;
      bestSelectedEdge.incrementNumVisits();
      mostLikelyWinner = -1;
    }

    //  Update the visit counts on the selection pass.  The update counts
    //  will be updated on the back-propagation pass
    numVisits++;
    selected.incrementNumVisits();

    return result;
  }

  public boolean isUnexpanded()
  {
    return mNumChildren == 0 || complete;
  }

  private double scoreForMostLikelyResponseRecursive(TreeNode from,
                                                     int forRoleIndex)
  {
    //	Stop recursion at the next choice
    if (mNumChildren == 0 || complete)
    {
      return getAverageScore(forRoleIndex);
    }
    else if ((decidingRoleIndex + 1) % tree.numRoles == forRoleIndex &&
        from != null && mNumChildren > 1)
    {
      return from.getAverageScore(forRoleIndex); //	TEMP TEMP TEMP
    }

    double result = 0;
    double childResult = -Double.MAX_VALUE;

    for (short index = 0; index < mNumChildren; index++)
    {
      if ( primaryChoiceMapping == null || primaryChoiceMapping[index] == index )
      {
        Object choice = children[index];

        TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
        if (edge != null && get(edge.mChildRef) != null)
        {
          TreeNode lNode = get(edge.mChildRef);
          double childVal = lNode.getAverageScore(lNode.decidingRoleIndex);

          if (childVal > childResult)//&& edge.child.node.numVisits > 500 )
          {
            childResult = childVal;
            result = lNode.scoreForMostLikelyResponseRecursive(this, forRoleIndex);
          }
        }
      }
    }

    return (childResult == -Double.MAX_VALUE ? getAverageScore(forRoleIndex)
        : Math
        .min(result,
             getAverageScore(forRoleIndex)));
  }

  private double scoreForMostLikelyResponse()
  {
    return scoreForMostLikelyResponseRecursive(null, decidingRoleIndex);
  }

  private String stringizeScoreVector()
  {
    StringBuilder sb = new StringBuilder();

    sb.append("[");
    for (int i = 0; i < tree.numRoles; i++)
    {
      if (i > 0)
      {
        sb.append(", ");
      }
      sb.append(FORMAT_2DP.format(getAverageScore(i)));
    }
    sb.append("]");
    if (complete)
    {
      sb.append(" (complete)");
    }

    return sb.toString();
  }

  private int traceFirstChoiceNode(int xiResponsesTraced)
  {
    if (mNumChildren == 0)
    {
      LOGGER.info("    No choice response scores " + stringizeScoreVector());
    }
    else if (mNumChildren > 1)
    {
      for (short index = 0; index < mNumChildren; index++)
      {
        if ( primaryChoiceMapping == null || primaryChoiceMapping[index] == index )
        {
          Object choice = children[index];

          TreeEdge edge2 = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
          if (edge2 != null && edge2.mChildRef != NULL_REF && get(edge2.mChildRef) != null)
          {
            TreeNode lNode2 = get(edge2.mChildRef);
            String lLog = "    Response " +
                          edge2.mPartialMove.move +
                          " scores " + lNode2.stringizeScoreVector() +
                          ", visits " + lNode2.numVisits +
                          ", ref : " + lNode2.mRef +
                          (lNode2.complete ? " (complete)" : "");

            if (xiResponsesTraced < 4000)
            {
              LOGGER.debug(lLog);
            }
            else
            {
              if (xiResponsesTraced == 400)
              {
                LOGGER.debug("(Further responses output at trace level)");
              }
              LOGGER.trace(lLog);
            }
            xiResponsesTraced++;
          }
          else if (choice instanceof TreeEdge)
          {
            edge2 = (TreeEdge)choice;

            if (edge2.mChildRef != NULL_REF)
            {
              TreeNode node = get(edge2.mChildRef);

              if ( node != null )
              {
                xiResponsesTraced = node.traceFirstChoiceNode(xiResponsesTraced);
              }
              else
              {
                String lLog = "    Response " + edge2.mPartialMove.move + " trimmed";

                if (xiResponsesTraced < 400)
                {
                  LOGGER.debug(lLog);
                }
              }
            }
            else
            {
              String lLog = "    Response " + edge2.mPartialMove.move + " unexpanded";

              if (xiResponsesTraced < 400)
              {
                LOGGER.debug(lLog);
              }
            }
          }
          else
          {
            String lLog = "    Response " +
                ((ForwardDeadReckonLegalMoveInfo)choice).move +
                " unexpanded edge";

            if (xiResponsesTraced < 400)
            {
              LOGGER.debug(lLog);
            }
          }
        }
      }
    }
    else if (children[0] instanceof TreeEdge)
    {
      TreeEdge edge2 = (TreeEdge)children[0];

      if (edge2.mChildRef != NULL_REF && get(edge2.mChildRef) != null )
      {
        xiResponsesTraced = get(edge2.mChildRef).traceFirstChoiceNode(xiResponsesTraced);
      }
      else
      {
        String lLog = "    Response " + edge2.mPartialMove.move + " unexpanded";

        if (xiResponsesTraced < 400)
        {
          LOGGER.debug(lLog);
        }
      }
    }
    else
    {
      String lLog = "    Response " +
          ((ForwardDeadReckonLegalMoveInfo)children[0]).move +
          " unexpanded edge";

      if (xiResponsesTraced < 400)
      {
        LOGGER.debug(lLog);
      }
    }

    return xiResponsesTraced;
  }

  private void indentedPrint(PrintWriter writer, int depth, String line)
  {
    StringBuilder indent = new StringBuilder();

    for (int i = 0; i < depth; i++)
    {
      indent.append(" ");
    }
    writer.println(indent + line);
  }

  private void dumpTree(PrintWriter writer, int depth, TreeEdge arrivalPath)
  {
    if (arrivalPath == null)
    {
      indentedPrint(writer, depth * 2, "Root scores " +
          stringizeScoreVector());
    }
    else
    {
      indentedPrint(writer,
                    depth * 2,
                    "@" +
                        depth +
                        ": Move " +
                        (arrivalPath.mPartialMove.inputProposition == null ? arrivalPath.mPartialMove.move : arrivalPath.mPartialMove.inputProposition.getName()) +
                        " (choosing role " + (decidingRoleIndex+1)%tree.numRoles + ")" +
                        " scores " + stringizeScoreVector() + "[" + heuristicValue + "@" + heuristicWeight + "] (ref " + mRef +
                        ") - visits: " + numVisits + " (" +
                        arrivalPath.getNumChildVisits() + ", " + arrivalPath.hasHeuristicDeviation + "), updates: " + numUpdates);
    }

    if (sweepSeq == tree.sweepInstance)
    {
      indentedPrint(writer, (depth + 1) * 2, "...transition...");
    }
    else
    {
      sweepSeq = tree.sweepInstance;

      for (short index = 0; index < mNumChildren; index++)
      {
        if ( primaryChoiceMapping == null || primaryChoiceMapping[index] == index )
        {
          Object choice = children[index];

          TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
          if (edge != null && edge.mChildRef != NULL_REF && get(edge.mChildRef) != null)
          {
            get(edge.mChildRef).dumpTree(writer, depth + 1, edge);
          }
          else
          {
            indentedPrint(writer,
                          (depth+1) * 2,
                          "@" +
                              (depth + 1) +
                              ": Move " +
                              (edge == null ? (ForwardDeadReckonLegalMoveInfo)choice : edge.mPartialMove).move +
                              " unexpanded");
          }
        }
      }
    }
  }

  void dumpTree(String filename)
  {
    tree.sweepInstance++;

    try
    {
      File f = new File(filename);
      PrintWriter writer = new PrintWriter(f);
      dumpTree(writer, 0, null);
      writer.close();
    }
    catch (Exception e)
    {
      GamerLogger.logStackTrace("StateMachine", e);
    }
  }

  public FactorMoveChoiceInfo getBestMove(boolean traceResponses, StringBuffer pathTrace)
  {
    double bestScore = -Double.MAX_VALUE;
    double bestMoveScore = -Double.MAX_VALUE;
    double bestRawScore = -Double.MAX_VALUE;
    TreeEdge rawBestEdgeResult = null;
    TreeEdge bestEdge = null;
    boolean anyComplete = false;
    TreeNode bestNode = null;
    FactorMoveChoiceInfo result = new FactorMoveChoiceInfo();
    int lResponsesTraced = 0;
    GdlConstant primaryMoveName = null;
    GdlConstant secondaryMoveName = null;
    TreeEdge firstPrimaryEdge = null;
    TreeEdge firstSecondaryEdge = null;
    int numPrimaryMoves = 0;
    int numSecondaryMoves = 0;

    //  If there is no pseudo-noop then there cannot be any penalty for not taking
    //  this factor's results - we simply return a pseudo-noop penalty value of 0
    result.pseudoNoopValue = 100;

    // This routine is called recursively for path tracing purposes.  When
    // calling this routing for path tracing purposes, don't make any other
    // debugging output (because it would be confusing).
    boolean lRecursiveCall = (pathTrace != null);

    // Find the role which has a choice at this node.  If this function is
    // being called for real (rather than for debug trace) it MUST be our role
    // (always 0), otherwise why are we trying to get the best move?
    int roleIndex = (decidingRoleIndex + 1) % tree.numRoles;
    assert(lRecursiveCall || roleIndex == 0);
    assert(mNumChildren != 0) : "Asked to get best move when there are NO CHILDREN!";

    int maxChildVisitCount = 1;
    if (!lRecursiveCall)
    {
      for (int lii = 0; lii < mNumChildren; lii++)
      {
        Object choice = children[lii];
        TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
        if (edge != null && edge.mChildRef != NULL_REF)
        {
          TreeNode lNode = get(edge.mChildRef);

          if ( lNode != null )
          {
            if ( lNode.numVisits > maxChildVisitCount )
            {
              maxChildVisitCount = lNode.numVisits;
            }
            if (lNode.complete)
            {
              anyComplete = true;
            }
          }
        }
      }
    }

    for (int lii = 0; lii < mNumChildren; lii++)
    {
      Object choice = children[lii];
      TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
      if (edge == null || edge.mChildRef == NULL_REF)
      {
        if ( !lRecursiveCall )
        {
          ForwardDeadReckonLegalMoveInfo partialMove;

          if ( edge  == null )
          {
            partialMove = (ForwardDeadReckonLegalMoveInfo)choice;
          }
          else
          {
            partialMove = edge.mPartialMove;
          }
          LOGGER.warn("Unexpanded child of root for move: " + partialMove.move);
        }
        continue;
      }

      //  If this factor is irrelevant it doesn't really matter what we pick, but noop
      //  seems to often be good for hindering the opponent!
      if ( tree.mIsIrrelevantFactor )
      {
        //  Whatever we choose will be worth 0 to us
        bestScore = 0;
        bestRawScore = 0;

        LOGGER.info("Move " + edge.descriptiveName() +
                    " in irrelevant factor");
        if ( edge.mPartialMove.inputProposition == null )
        {
          //  Any move not connected to the propnet at all is certainly a noop!
          bestEdge = edge;
          break;
        }

        //  If the noop is used in propnet calculations (e.g. - base props that remain unchanged when a noop happens)
        //  then we cannot spot them as easily, so we work on the assumption that the common pattern will be lots of
        //  some move name (e.g. 'Move' x y w z) and a lone noop of a different name.  Heuristically therefore play
        //  the loner.  Note that this isn't 100% reliable, but at the end of the day this is an irrelevant factor and
        //  if we fail to noop in it in some games that's hardly the end of the world.  A counter example would be
        //  a checkers position where you have both 'move' and 'jump' available
        GdlConstant moveName = edge.mPartialMove.move.getContents().toSentence().getName();

        if ( primaryMoveName == null )
        {
          primaryMoveName = moveName;
          firstPrimaryEdge = edge;
          bestEdge = edge;  //  bank a choice in case we never meet another choice condition
        }

        if ( primaryMoveName.equals(moveName) )
        {
          numPrimaryMoves++;
        }
        else
        {
          if ( secondaryMoveName == null )
          {
            secondaryMoveName = moveName;
            firstSecondaryEdge = edge;
          }
          if ( secondaryMoveName.equals(moveName) )
          {
            numSecondaryMoves++;
          }
        }

        if ( numPrimaryMoves > 1 && numSecondaryMoves == 1 )
        {
          bestEdge = firstSecondaryEdge;
          break;
        }
        if ( numPrimaryMoves == 1 && numSecondaryMoves > 1 )
        {
          bestEdge = firstPrimaryEdge;
          break;
        }
      }
      else
      {
        TreeNode child = get(edge.mChildRef);

        //  Trimmed nodes may be encountered anywhere below the root's own child links
        //  and these should not accidentally be followed when tracing a move path
        if (child == null)
        {
          continue;
        }

        double selectionScore;
        double moveScore = (tree.gameCharacteristics.isSimultaneousMove ||
                            tree.gameCharacteristics.numRoles > 2 ||
                            anyComplete ||
                            MCTSTree.DISABLE_ONE_LEVEL_MINIMAX) ? child.getAverageScore(roleIndex) :
                                                                  child.scoreForMostLikelyResponse();

        assert(-EPSILON <= moveScore && 100 + EPSILON >= moveScore);
        //	If we have complete nodes with equal scores choose the one with the highest variance
        if (child.complete)
        {
          if (moveScore < 0.1)
          {
            //  Prefer more distant losses to closer ones
            moveScore = (child.completionDepth - tree.mGameSearcher.getRootDepth()) - 500;
            assert(moveScore <= 0);
            assert(moveScore >= -500);
          }

          //  A complete score is certain, but we're comparing within a set that has only
          //  has numVisits TOTAL visits so still down-weight by the same visit count the most
          //  selected child has.  This avoids a tendency to throw a marginal win away for a
          //  definite draw.  Especially in games with low signal to noise ratio (everything looks
          //  close to 50%) this can be important
          selectionScore = moveScore *
              (1 - 20 * Math.log(numVisits) /
                  (20 * Math.log(numVisits) + maxChildVisitCount));
        }
        else
        {
          int numChildVisits = child.numVisits;

          //  Cope with the case where root expansion immediately found a complete node and never
          //  even explored the others (which should not be selected)
          if (numChildVisits == 0)
          {
            selectionScore = -1000;
          }
          else
          {
            //  Subtly down-weight noops in 1-player games to discourage them.  Note that
            //  this has to be fairly subtle, and not impact asymptotic choices since it is possible
            //  for a puzzle to require noops for a solution!
            if (tree.gameCharacteristics.numRoles == 1)
            {
              if (edge.mPartialMove.inputProposition == null)
              {
                numChildVisits /= 2;
              }
            }
            selectionScore = moveScore *
                (1 - 20 * Math.log(numVisits) /
                    (20 * Math.log(numVisits) + numChildVisits));
          }
        }
        if (!lRecursiveCall)
        {
          LOGGER.info("Move " + edge.descriptiveName() +
                      " scores " + FORMAT_2DP.format(moveScore) + " (selectionScore score " +
                      FORMAT_2DP.format(selectionScore) + ", selection count " +
                      child.numVisits + ", ref " + child.mRef +
                      (child.complete ? (", complete [" + ((child.completionDepth - tree.root.depth)/tree.numRoles) + "]") : "") + ")");
        }

        if (child.mNumChildren != 0 && !child.complete && traceResponses)
        {
          lResponsesTraced = child.traceFirstChoiceNode(lResponsesTraced);
        }

        if (edge.mPartialMove.isPseudoNoOp)
        {
          result.pseudoNoopValue = moveScore;
          result.pseudoMoveIsComplete = child.complete;
          continue;
        }
        //	Don't accept a complete score which no rollout has seen worse than, if there is
        //	any alternative
        if (bestNode != null && !bestNode.complete && child.complete &&
            moveScore < tree.lowestRolloutScoreSeen &&
            tree.lowestRolloutScoreSeen < 100)
        {
          continue;
        }
        if (bestNode == null ||
            selectionScore > bestScore ||
            (selectionScore == bestScore && child.complete && (child.completionDepth < bestNode.completionDepth || !bestNode.complete)) ||
            (bestNode.complete && !child.complete &&
            bestNode.getAverageScore(roleIndex) < tree.lowestRolloutScoreSeen && tree.lowestRolloutScoreSeen < 100))
        {
          bestNode = child;
          bestScore = selectionScore;
          bestMoveScore = bestNode.getAverageScore(0);
          bestEdge = edge;
        }
        if (child.getAverageScore(roleIndex) > bestRawScore ||
            (child.getAverageScore(roleIndex) == bestRawScore && child.complete && child.getAverageScore(roleIndex) > 0))
        {
          bestRawScore = child.getAverageScore(roleIndex);
          rawBestEdgeResult = edge;
        }
      }
    }

    //dumpTree("C:\\temp\\mctsTree.txt");

    if (!lRecursiveCall)
    {
      if (bestEdge == null && tree.factor == null)
      {
        LOGGER.warn("No move found!");
      }
      if (rawBestEdgeResult != bestEdge)
      {
        LOGGER.info("1 level minimax result differed from best raw move: " + rawBestEdgeResult);
      }
    }

    // Trace the most likely path through the tree
    if (!lRecursiveCall)
    {
      pathTrace = new StringBuffer("Most likely path: ");
    }
    assert(pathTrace != null);
    if (bestEdge != null)
    {
      pathTrace.append(bestEdge.descriptiveName());
      pathTrace.append(roleIndex == 0 ? ", " : " | ");
    }

    if ((bestNode != null) && (bestNode.mNumChildren != 0))
    {
      bestNode.getBestMove(false, pathTrace);
    }
    else
    {
      LOGGER.info(pathTrace.toString());
    }

    if (bestEdge == null)
    {
      result.bestMove = null;
    }
    else
    {
      ForwardDeadReckonLegalMoveInfo moveInfo = bestEdge.mPartialMove;

      result.bestMove = (moveInfo.isPseudoNoOp ? null : moveInfo.move);
      if (!moveInfo.isPseudoNoOp)
      {
        result.bestMoveValue = bestMoveScore;
        result.bestMoveIsComplete = get(bestEdge.mChildRef).complete;
      }
    }

    return result;
  }

  /**
   * Perform a rollout.
   *
   * @param path             - the path to the selected node.
   * @param xiPipeline       - the pipeline from which RolloutRequests can be allocated.
   * @param forceSynchronous - whether this rollout *must* be performed synchronously.
   * @param xiSelectTime     - the elapsed time to select the node.
   * @param xiExpandTime     - the elapsed time to expand the node.
   *
   * @throws MoveDefinitionException
   * @throws TransitionDefinitionException
   * @throws GoalDefinitionException
   */
  public void rollOut(TreePath path,
                      Pipeline xiPipeline,
                      boolean forceSynchronous,
                      long xiSelectTime,
                      long xiExpandTime)
    throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
    //  Rolling out from this node constitutes a visit, and the leaf node that we roll out
    //  from will not have had its visit count updated by select as it has not been selected through
    numVisits++;

    assert(!freed) : "Rollout node is a freed node";
    assert(path.isValid()) : "Rollout path isn't valid";

    if (complete)
    {
      // This node is already complete, so there's no need to perform another rollout.  Just back-propagate the known
      // score for this node.
      tree.numTerminalRollouts++;

      // Take a copy of the scores because updateStats may modify these values during back-propagation.
      for (int i = 0; i < tree.numRoles; i++)
      {
        tree.mNodeAverageScores[i] = getAverageScore(i);
        tree.mNodeAverageSquaredScores[i] = getAverageSquaredScore(i);
      }

      long lBackPropTime = updateStats(tree.mNodeAverageScores,
                                       tree.mNodeAverageSquaredScores,
                                       path,
                                       1,//Math.pow(tree.mWeightDecay, depth),
                                       true);
      tree.mGameSearcher.recordIterationTimings(xiSelectTime, xiExpandTime, 0, 0, 0, 0, lBackPropTime);
      tree.mPathPool.free(path);

      return;
    }

    //assert(decidingRoleIndex == tree.numRoles - 1) : "Attempt to rollout from an incomplete-information node";

    assert(!freed) : "Rollout node is a freed node";
    assert(path.isValid()) : "Rollout path isn't valid";

    // Get a rollout request object.
    RolloutRequest lRequest;
    long lGetSlotTime = 0;
    if (ThreadControl.ROLLOUT_THREADS > 0 && !forceSynchronous)
    {
      // Get a request slot from the pipeline.
      if (!xiPipeline.canExpand())
      {
        // The pipeline is full.  We can't expand it until we've done some back-propagation.  Even though none was
        // available at the start of the expansion, we'll just have to wait.
        lGetSlotTime = tree.mGameSearcher.processCompletedRollouts(true);

        //  Processing completions above could have resulted in a node on the rollout
        //  path from being freed (because it has been determined to be complete or an
        //  ancestor has).  In such cases abort the rollout.
        if (path.isFreed())
        {
          tree.mPathPool.free(path);
          return;
        }
      }
      lRequest = xiPipeline.getNextExpandSlot();
    }
    else
    {
      // Synchronous rollouts - use the single request object.
      lRequest = tree.mNodeSynchronousRequest;
    }

    assert(!freed) : "Rollout node is a freed node";
    assert(path.isValid()) : "Rollout path isn't valid";

    lRequest.mSelectElapsedTime  = xiSelectTime;
    lRequest.mExpandElapsedTime  = xiExpandTime;
    lRequest.mGetSlotElapsedTime = lGetSlotTime;
    lRequest.mState.copy(state);
    lRequest.mNodeRef = getRef();
    lRequest.mSampleSize = tree.gameCharacteristics.getRolloutSampleSize();
    lRequest.mPath = path;
    lRequest.mFactor = tree.factor;
    lRequest.mPlayedMovesForWin = ((tree.gameCharacteristics.isPseudoPuzzle && tree.factor == null) ? new LinkedList<ForwardDeadReckonLegalMoveInfo>() : null);
    lRequest.mTree = tree;

    //request.moveWeights = masterMoveWeights.copy();
    tree.numNonTerminalRollouts += lRequest.mSampleSize;

    if (lRequest != tree.mNodeSynchronousRequest)
    {
      // Queue the request for processing.
      lRequest.mEnqueueTime = System.nanoTime();
      xiPipeline.completedExpansion();
    }
    else
    {
      // Do the rollout and back-propagation synchronously (on this thread).
      assert(ThreadControl.ROLLOUT_THREADS == 0 || forceSynchronous);
      lRequest.process(tree.underlyingStateMachine, tree.mOurRole, tree.roleOrdering);
      long lRolloutTime = System.nanoTime() - lRequest.mRolloutStartTime;
      assert(!Double.isNaN(lRequest.mAverageScores[0]));
      long lBackPropTime = updateStats(lRequest.mAverageScores,
                                       lRequest.mAverageSquaredScores,
                                       lRequest.mPath,
                                       lRequest.mWeight,
                                       false);
      tree.mGameSearcher.recordIterationTimings(xiSelectTime, xiExpandTime, 0, 0, lRolloutTime, 0, lBackPropTime);
      tree.mPathPool.free(lRequest.mPath);
      lRequest.mPath = null;
    }
  }

  /**
   * Update the tree with details from a rollout, starting with this node, then working up the path.
   *
   * WARNING: The caller is responsible for ensuring that the entire path is valid before calling this function.
   *
   * @param xiValues                  - The per-role rollout values.
   * @param xiSquaredValues           - The per-role squared values (for computing variance).
   * @param xiPath                    - The path taken through the tree for the rollout.
   * @param xiIsCompletePseudoRollout - Whether this is a complete pseudo-rollout.
   *
   * @return the time taken to do the update, in nanoseconds
   */
  public long updateStats(double[] xiValues,
                          double[] xiSquaredValues,
                          TreePath xiPath,
                          double  xiWeight,
                          boolean xiIsCompletePseudoRollout)
  {
    long lStartTime = System.nanoTime();
    assert(checkFixedSum(xiValues));
    assert(xiPath.isValid());

    boolean heuristicAdjustmentApplied = false;
    TreeNode lNextNode;
    for (TreeNode lNode = this; lNode != null; lNode = lNextNode)
    {
      TreePathElement lElement = xiPath.getCurrentElement();
      TreeEdge lChildEdge = (lElement == null ? null : lElement.getEdgeUnsafe());
      lNextNode = null;

      if (!heuristicAdjustmentApplied && lNode.heuristicWeight > 0 && !xiIsCompletePseudoRollout )
      {
        double applicableValue = (lNode.heuristicValue > 50 ? lNode.heuristicValue : 100 - lNode.heuristicValue);

        if ( applicableValue > EPSILON )
        {
          for(int roleIndex = 0; roleIndex < tree.numRoles; roleIndex++)
          {
            double heuristicAdjustedValue;

            if ((lNode.heuristicValue > 50) == (roleIndex == 0))
            {
              heuristicAdjustedValue = xiValues[roleIndex] + (100 - xiValues[roleIndex]) * (applicableValue - 50) / 50;
            }
            else
            {
              heuristicAdjustedValue = xiValues[roleIndex] - (xiValues[roleIndex]) * (applicableValue - 50) / 50;
            }

            xiValues[roleIndex] = heuristicAdjustedValue;
            xiSquaredValues[roleIndex] = heuristicAdjustedValue*heuristicAdjustedValue;
          }
        }
      }

      double applicationWeight = xiWeight;

      //  For a non-decisive losing complete node we know this path will not actually be chosen
      //  so reduce its weight significantly.  This helps a lot in games like breakthrough where
      //  all responses but one (to an enemy pawn advancing the 7th/2nd rank) lose, by preventing
      //  the necessary expansion of every child resulting in a very misleading value for the immediate
      //  parent after O(branching factor) visits, which can otherwise cause it to sometimes not be
      //  explored further
      if ( xiIsCompletePseudoRollout && xiValues[(lNode.decidingRoleIndex+1)%tree.numRoles] == 0 )
      {
        applicationWeight /= 10;
      }

      // Across a turn end it is possible for queued paths to run into freed nodes due to trimming of the tree at the
      // root to advance the turn.  Rather than add locking and force clearing the rollout pipeline synchronously on
      // turn start it is more efficient to simply abort the update when the path leads to a no-longer extant region of
      // the tree.
      if (xiPath.hasMore())
      {
        lNextNode = xiPath.getNextNode();
        if (lNextNode == null)
        {
          return System.nanoTime() - lStartTime;
        }
      }

      // Do the stats update for the selected node.
      //assert(lNode.numUpdates <= lNode.numVisits);

      double[] lOverrides = (lElement == null ? null : lElement.getScoreOverrides());
      if (lOverrides != null)
      {
        xiValues = lOverrides;
        assert(checkFixedSum(xiValues));
        for (int lRoleIndex = 0; lRoleIndex < tree.numRoles; lRoleIndex++)
        {
          xiSquaredValues[lRoleIndex] = xiValues[lRoleIndex]*xiValues[lRoleIndex];
        }
      }

      for (int lRoleIndex = 0; lRoleIndex < tree.numRoles; lRoleIndex++)
      {
        assert(xiValues[lRoleIndex] < 100+EPSILON);
        if ((!lNode.complete ||
             tree.gameCharacteristics.isSimultaneousMove ||
             tree.gameCharacteristics.numRoles > 2) &&
            lChildEdge != null)
        {
          TreeNode lChild = lNode.get(lChildEdge.mChildRef);
          //  Take the min of the apparent edge selection and the total num visits in the child
          //  This is necessary because when we re-expand a node that was previously trimmed we
          //  leave the edge with its old selection count even though the child node will be
          //  reset.
          int lNumChildVisits = Math.min(lChildEdge.getNumChildVisits(), lChild.numVisits);

          assert(lNumChildVisits > 0 || xiIsCompletePseudoRollout);
          //  Propagate a value that is a blend of this rollout value and the current score for the child node
          //  being propagated from, according to how much of that child's value was accrued through this path
          if (xiValues != lOverrides && lNumChildVisits > 0)
          {
            xiValues[lRoleIndex] = (xiValues[lRoleIndex] * lNumChildVisits + lChild.getAverageScore(lRoleIndex) *
                                    (lChild.numVisits - lNumChildVisits)) /
                                   lChild.numVisits;
            assert(xiValues[lRoleIndex] < 100+EPSILON);
          }
        }

        if (!lNode.complete)
        {
          lNode.setAverageScore(lRoleIndex,
                                (lNode.getAverageScore(lRoleIndex) * lNode.numUpdates * scoreTemporalDecayRate + xiValues[lRoleIndex]*applicationWeight) /
                                (lNode.numUpdates*scoreTemporalDecayRate + applicationWeight));
          lNode.setAverageSquaredScore(lRoleIndex,
                                       (lNode.getAverageSquaredScore(lRoleIndex) *
                                        lNode.numUpdates + xiSquaredValues[lRoleIndex]*applicationWeight) /
                                       (lNode.numUpdates + applicationWeight));
        }

        lNode.leastLikelyWinner = -1;
        lNode.mostLikelyWinner = -1;
      }

      //validateScoreVector(averageScores);
      lNode.numUpdates += applicationWeight;
      //assert(lNode.numUpdates <= lNode.numVisits);

      assert(checkFixedSum(xiValues));
      assert(checkFixedSum());
    }

    return System.nanoTime() - lStartTime;
  }

  /**
   * Get a node by reference, from the specified pool.
   *
   * @param xiPool    - the pool.
   * @param xiNodeRef - the node reference.
   *
   * @return the node, or null if it has been recycled.
   */
  public static TreeNode get(CappedPool<TreeNode> xiPool, long xiNodeRef)
  {
    assert(xiNodeRef != NULL_REF);

    TreeNode lNode = xiPool.get((int)xiNodeRef);
    if (lNode.mRef == xiNodeRef)
    {
      return lNode;
    }
    return null;
  }

  /**
   * Convenience method to get a TreeNode by reference from the same pool as this node.
   *
   * @param xiNodeRef - the node reference.
   *
   * @return the referenced node, or null if it has been recycled.
   */
  private TreeNode get(long xiNodeRef)
  {
    return get(tree.nodePool, xiNodeRef);
  }
}