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

  private static final String TEMP_DIR = System.getProperty("java.io.tmpdir");

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
  boolean                               hasBeenLocalSearched = false;
  ForwardDeadReckonLegalMoveInfo        isLocalLossFrom     = null;
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
  short                         completionDepth;
  private double                        heuristicValue;
  private double                        heuristicWeight;

  //  To what depth is the hypr-linkage tree expanded from this node
  private short                         hyperExpansionDepth = 0;

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
    assert(this != tree.root);
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
              if (edge != null && edge.getChildRef() != NULL_REF && get(edge.getChildRef()) == this)
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
            if (edge != null && edge.getChildRef() != NULL_REF && get(edge.getChildRef()) != null)
            {
              TreeNode lChild = get(edge.getChildRef());

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
        if (edge != null && edge.getChildRef() != NULL_REF && get(edge.getChildRef()) != null)
        {
          TreeNode lChild = get(edge.getChildRef());
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

  void markAsLocalLoss(short atDepth, ForwardDeadReckonLegalMoveInfo withMove)
  {
    if ( !complete )
    {
      isLocalLossFrom = withMove;
      completionDepth = (short)(depth + atDepth);
    }
  }

  void markComplete(double[] values, short atCompletionDepth)
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
    //validateAll();
    //	Children can all be freed, at least from this parentage
    if (MCTSTree.FREE_COMPLETED_NODE_CHILDREN)
    {
      boolean keepAll = false;
      boolean keepBest = false;

      if ( MCTSTree.KEEP_BEST_COMPLETION_PATHS )
      {
        if ( decidingRoleIndex == tree.numRoles-1 )
        {
          //  Our choice from this node - just retain the best
          keepBest = true;
        }
        else
        {
          //  Opponent choice from this node - need to keep all since they might play any
          keepAll = true;
        }
      }

      if ( !keepAll )
      {
        int keepIndex = -1;

        if ( keepBest )
        {
          double bestScore = 0;

          for (int index = 0; index < mNumChildren; index++)
          {
            if ( primaryChoiceMapping == null || primaryChoiceMapping[index] == index )
            {
              Object choice = children[index];

              TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
              if (edge != null)
              {
                //  Completion processing happens via the direct link tree only, so ignore hyper-edges
                if ( edge.hyperSuccessor != null)
                {
                  break;
                }

                TreeNode lChild = (edge.getChildRef() == NULL_REF ? null : get(edge.getChildRef()));

                if ( lChild != null && lChild.complete && lChild.getAverageScore(0) > bestScore )
                {
                  bestScore = lChild.getAverageScore(0);
                  keepIndex = index;
                }
              }
            }
          }
        }

        if ( keepIndex != -1 || this != tree.root )
        {
          for (int index = 0; index < mNumChildren; index++)
          {
            if ( primaryChoiceMapping == null || primaryChoiceMapping[index] == index )
            {
              Object choice = children[index];

              TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
              if (edge != null)
              {
                if ( keepIndex != index )
                {
                  TreeNode lChild = (edge.getChildRef() == NULL_REF || edge.isHyperEdge()) ? null : get(edge.getChildRef());

                  deleteEdge(index);
                  if (lChild != null)
                  {
                    lChild.freeFromAncestor(this, null);
                  }
                }
                else
                {
                  //  Must make sure the edge we retain becomes selectable if it was previously handled by
                  //  a hyper-edge alternative
                  edge.setIsSelectable(true);
                }
              }
            }
          }

          if ( keepIndex == -1 )
          {
            assert(this != tree.root);
            //  Actually retained nothing - can get rid of the children entirely
            mNumChildren = 0;
          }
        }
      }
    }

    for (TreeNode parent : parents)
    {
      if ( !parent.complete )
      {
        boolean decidingRoleWin = false;
        boolean mutualWin = true;
        //  Because we link directly through force-move sequences the deciding role in the completed
        //  node may not be the role that chose that path from the parent - we must check what the choosing
        //  role on the particular parent was
        int choosingRoleIndex = (parent.decidingRoleIndex+1)%tree.numRoles;

        for (int roleIndex = 0; roleIndex < tree.numRoles; roleIndex++)
        {
          //  Don't take wins by a player other than us in a non fixed-sum >2 player game as auto-propagating
          //  or else we may search only on a win path that is not the pessimal win-path for us of those that are all
          //  wins for the opponent concerned (if it's our win just take it and don;t worry if we could possibly
          //  make them suffer worse-  better to converge quickly)
          tree.underlyingStateMachine.getLatchedScoreRange(parent.state, tree.roleOrdering.roleIndexToRole(roleIndex), tree.latchedScoreRangeBuffer);
          if ( tree.latchedScoreRangeBuffer[1] > tree.latchedScoreRangeBuffer[0] &&
               getAverageScore(roleIndex) > tree.latchedScoreRangeBuffer[1] - EPSILON &&
               (choosingRoleIndex == 0 || (tree.gameCharacteristics.getIsFixedSum() && tree.numRoles < 3)) )
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

    if ( tree != null )
    {
      //  If the child array was above a certain threshold (due to having been used for a large number
      //  of hyper-edges, which will not be a dominant case) reduce the allocation back to the high
      //  watermark for direct children
      int directChildHighWatermark = tree.gameCharacteristics.getChoicesHighWaterMark(0);
      if ( children.length > directChildHighWatermark*2 )
      {
        children = new Object[directChildHighWatermark];
      }
    }
  }

  private boolean validateHasChild(TreeNode child)
  {
    boolean result = false;

    for(int i = 0; i < mNumChildren; i++)
    {
      if ( (children[i] instanceof TreeEdge) && ((TreeEdge)children[i]).getChildRef() == child.getRef() )
      {
        result = true;
        break;
      }
    }

    return result;
  }

  boolean validateHyperChain(TreeEdge edge)
  {
    if ( complete )
    {
      return true;
    }

    if ( edge.mParentRef != getRef() )
    {
      //  Stale hyper-edge - can happen after trimming
      return true;
    }

    if ( edge.hyperSuccessor == null )
    {
      if ( edge.getChildRef() != NULL_REF )
      {
        TreeNode child = get(edge.getChildRef());

        if ( child != null )
        {
          assert(child.depth/2 > depth/2);
          //assert(depth/2 == state.size()-3);
        }
      }
    }
    else
    {
      if ( edge.getChildRef() != edge.hyperSuccessor.getChildRef() )
      {
        //  Stale hyper-edge - can happen after trimming
        return true;
      }

      TreeNode child = get(edge.nextHyperChild);

      if ( child == null )
      {
        assert(false) : "Null node link in the middle of a hyper chain";
        return false;
      }
      if (child.depth <= 115 && child.depth/2 != depth/2 + 1)
      {
        assert(false) : "Unexpected depth change across on link of hyper-chain";
        return false;
      }

      child.validateHyperChain(edge.hyperSuccessor);
    }

    return true;
  }

  boolean linkageValid()
  {
    if ( mNumChildren > 0 )
    {
      boolean hyperEdgeSeen = false;

      for(short i = 0; i < mNumChildren; i++)
      {
        if ( children[i] instanceof TreeEdge )
        {
          TreeEdge edge = (TreeEdge)children[i];

          if ( edge.isHyperEdge() )
          {
            hyperEdgeSeen = true;
          }
          else if ( hyperEdgeSeen )
          {
            assert(false) : "normal edge after start of hyper edges";
            return false;
          }
          if ( edge.getChildRef() != NULL_REF )
          {
            TreeNode child = get(edge.getChildRef());

            if ( child != null )
            {
              if ( edge.hyperSuccessor == null && child != tree.root && !child.parents.contains(this) )
              {
                assert(false) : "child link not reflected in back-link";
                return false;
              }

              if ( !edge.isHyperEdge() )
              {
                for(short j = 0; j < mNumChildren; j++)
                {
                  if ( i != j && children[j] instanceof TreeEdge )
                  {
                    TreeEdge edge2 = (TreeEdge)children[j];
                    TreeNode child2 = (edge2.getChildRef() == NULL_REF ? null : get(edge2.getChildRef()));

                    if ( child == child2 && edge.isSelectable() )
                    {
                      assert(false) : "multiply linked child";
                      return false;
                    }
                  }
                }
              }
              else
              {
                validateHyperChain(edge);
              }
            }
            else if ( edge.hyperSuccessor == null )
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
          lChild = (edge.getChildRef() == NULL_REF || edge.isHyperEdge()) ? null : get(edge.getChildRef());

          deleteEdge(index);

          //  No need to traverse hyper-edges in this process.  Also, since hyper-edges
          //  always follow the full set of direct edges in the array as soon as we see one
          //  we can stop looking
          if ( edge.hyperSuccessor != null )
          {
            break;
          }
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
          if (edge == null || edge.getChildRef() == NULL_REF || get(edge.getChildRef()) != this)
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
          if (edge == null || edge.getChildRef() == NULL_REF)
          {
            return false;
          }

          TreeNode child = get(edge.getChildRef());
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
                    if (nephewEdge == null || nephewEdge.getChildRef() == NULL_REF)
                    {
                      return false;
                    }

                    TreeNode nephew = get(nephewEdge.getChildRef());

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
          if (edge != null && edge.getChildRef() != NULL_REF)
          {
            TreeNode child = get(edge.getChildRef());
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
          if (edge == null || edge.getChildRef() == NULL_REF)
          {
            return false;
          }

          TreeNode child = get(edge.getChildRef());
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
                  if (nephewEdge == null || nephewEdge.getChildRef() == NULL_REF)
                  {
                    if (moves.contains((nephewEdge == null ? (ForwardDeadReckonLegalMoveInfo)nephewChoice : nephewEdge.mPartialMove).move))
                    {
                      return false;
                    }
                    continue;
                  }
                  TreeNode nephew = get(nephewEdge.getChildRef());
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
          if (edge == null || edge.getChildRef() == NULL_REF)
          {
            return null;
          }
          TreeNode child = get(edge.getChildRef());

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
                if (nephewEdge == null || nephewEdge.getChildRef() == NULL_REF)
                {
                  return null;
                }

                TreeNode nephew = get(nephewEdge.getChildRef());
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

  @SuppressWarnings("null") void checkChildCompletion(boolean checkConsequentialSiblingCompletion)
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
    double selectedNonDeciderScore = Double.MAX_VALUE;

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

        //  Check for a freed stale hyper-edge (if we're onto hyper edges we can stop anyway)
        if ( choice == null )
        {
          break;
        }

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
          //  No need to process hyper-edges to determine completion
          if ( edge.hyperSuccessor != null )
          {
            break;
          }

          if (edge.getChildRef() == NULL_REF)
          {
            allImmediateChildrenComplete = false;
          }
          else if (get(edge.getChildRef()) != null)
          {
            TreeNode lNode = get(edge.getChildRef());
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
              //  assume that the one with the worst score for us will be chosen.  If we are the decider
              //  assume that which minimizes the sum of opponents is chosen
              double deciderScore = lNode.getAverageScore(roleIndex);
              double nonDeciderScore;

              if ( roleIndex == 0 )
              {
                nonDeciderScore = 0;

                for(int opponentRoleIndex = 0; opponentRoleIndex < tree.numRoles; opponentRoleIndex++)
                {
                  if ( opponentRoleIndex != roleIndex )
                  {
                    nonDeciderScore += lNode.getAverageScore(opponentRoleIndex);
                  }
                }
              }
              else
              {
                nonDeciderScore = lNode.getAverageScore(0);
              }
              if ( deciderScore > bestValue || (deciderScore == bestValue && nonDeciderScore < selectedNonDeciderScore))
              {
                selectedNonDeciderScore = nonDeciderScore;
                bestValue = deciderScore;
                bestValueNode = lNode;

                //  Force complete on a known win for us, but NOT for other roles, except in
                //  2 player fixed-sum games.  This is because in non-fixed-sum games it may be
                //  that a player has several winning moves, and we want to evaluate the worst one for us
                //  which may not be the first one to complete
                if ((roleIndex == 0 || (tree.gameCharacteristics.getIsFixedSum() && tree.numRoles < 3)) && bestValue > tree.roleMaxScoresBuffer[roleIndex]-EPSILON)
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
              if ( edge.hyperSuccessor != null )
              {
                break;
              }
              if (edge.getChildRef() != NULL_REF && get(edge.getChildRef()) != null)
              {
                TreeNode lNode = get(edge.getChildRef());
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

                assert(edge.getChildRef() != NULL_REF);

                TreeNode lNode = get(edge.getChildRef());
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
    freeChildren();

    // Reset primitives.
    numVisits = 0;
    numUpdates = 0;
    isTerminal = false;
    autoExpand = false;
    hasBeenLocalSearched = false;
    isLocalLossFrom = null;
    leastLikelyWinner = -1;
    mostLikelyWinner = -1;
    complete = false;
    allChildrenComplete = false;
    assert(freed || (xiTree == null));
    freed = (xiTree == null);
    depth = -1;
    sweepSeq = 0;
    //sweepParent = null;
    heuristicValue = 0;
    heuristicWeight = 0;
    hyperExpansionDepth = 0;

    // Reset objects (without allocating new ones).
    tree = xiTree;
    parents.clear();
    state.clear();

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

  TreeNode getChild(short index)
  {
    assert(index < mNumChildren);

    Object choice = children[index];
    TreeEdge edge;

    if ( choice instanceof TreeEdge )
    {
      edge = (TreeEdge)choice;
    }
    else
    {
      return null;
    }

    if ( edge.getChildRef() == NULL_REF )
    {
      return null;
    }

    return get(edge.getChildRef());
  }

  TreeNode createChildIfNeccessary(short index, ForwardDeadReckonLegalMoveInfo[] jointPartialMove, int choosingRoleIndex)
  {
    assert(index < mNumChildren);

    Object choice = children[index];
    TreeEdge edge;

    if ( choice instanceof TreeEdge )
    {
      edge = (TreeEdge)choice;
    }
    else
    {
      edge = tree.edgePool.allocate(tree.mTreeEdgeAllocator);
      edge.setParent(this, (ForwardDeadReckonLegalMoveInfo)children[index]);
      children[index] = edge;

      assert(edge != null);
    }

    if ( edge.getChildRef() == NULL_REF )
    {
      //  Cannot re-create children of hyper-edges - once hey are gone the hyper-edge is dead
      if ( edge.hyperSuccessor != null )
      {
        return null;
      }

      jointPartialMove[choosingRoleIndex] = edge.mPartialMove;

      createChildNodeForEdge(edge, jointPartialMove);

      assert(linkageValid());
    }

    return get(edge.getChildRef());
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
          TreeNode lNode = get(edge.getChildRef());
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
            if (edge != null && edge.getChildRef() != NULL_REF && get(edge.getChildRef()) == this)
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
          if (edge != null)
          {
            //  No need to traverse hyper-edges in this sweep.  Also, since hyper-edges
            //  always follow the full set of direct edges in the array as soon as we see one
            //  we can stop looking
            if ( edge.hyperSuccessor != null )
            {
              break;
            }
            if (edge.getChildRef() != NULL_REF && get(edge.getChildRef()) != null)
            {
              get(edge.getChildRef()).markTreeForSweep(this);
            }
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
  }

  public void freeAllBut(TreeNode descendant)
  {
    LOGGER.info("Free all but rooted in state: " + descendant.state);

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
        if (edge != null)
        {
          //  Note that hyper-edges represent duplicate paths that are not back-linked
          //  by parentage, so node-freeing via hyper-edges is not needed (or correct)
          TreeNode lNode = (edge.getChildRef() == NULL_REF || edge.hyperSuccessor != null) ? null : get(edge.getChildRef());

          // Delete our edge to the child anyway.  (We only set "descendant" when re-rooting the tree.  In that case,
          // we don't need the edge any more.)
          deleteEdge(index);

          // Free the child (at least from us)
          if ( lNode != null )
          {
            lNode.freeFromAncestor(this, descendant);
          }
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
    if ( lEdge.hyperSuccessor == null )
    {
      children[xiChildIndex] = lEdge.mPartialMove;
    }
    else
    {
      children[xiChildIndex] = null;
    }

    //  Make sure it is reset when freed not just whn re-allocated as it may still
    //  be referenced by a hyper-path, which will check validity via the refs
    lEdge.reset();
    // Return the edge to the pool.
    tree.edgePool.free(lEdge);
  }

  private void deleteHyperEdge(int xiChildIndex)
  {
    //  If the hyper-edge bing deleted is the last on through this mov then
    //  the principal (non-hyper) edge for that move must become selectable
    //  again
    Move move = ((TreeEdge)children[xiChildIndex]).mPartialMove.move;

    deleteEdge(xiChildIndex);

    TreeEdge principalEdge = null;

    for(int i = 0; i < mNumChildren; i++)
    {
      Object choice = children[i];

      if ( choice instanceof TreeEdge )
      {
        TreeEdge edge = (TreeEdge)choice;

        if ( edge.mPartialMove.move == move )
        {
          if ( edge.hyperSuccessor == null )
          {
            principalEdge = edge;
          }
          else
          {
            //  Still extant hypr-edge
            return;
          }
        }
      }
    }

    if ( principalEdge != null )
    {
      assert(!principalEdge.isSelectable());
      principalEdge.setIsSelectable(true);
    }
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
          if (edge.getChildRef() != NULL_REF && get(edge.getChildRef()) != null)
          {
            TreeNode childResult = get(edge.getChildRef()).findNode(targetState, maxDepth - 1);
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
    TreeEdge leastLikely = selectLeastLikelyExpandedNode(null);

    if (leastLikely != null)
    {
      //  Free the children of the chosen node from its parentage
      //  and de-expand it
      leastLikely.setHasBeenTrimmed();
      get(leastLikely.getChildRef()).unexpand();
      //validateAll();

      return true;
    }

    return false;
  }

  private void unexpand()
  {
    assert(mNumChildren != 0);
    assert(linkageValid());

    for (int index = 0; index < mNumChildren; index++)
    {
      Object choice = children[index];
      if (primaryChoiceMapping == null || primaryChoiceMapping[index] == index)
      {
        if ( choice instanceof TreeEdge )
        {
          TreeEdge edge = (TreeEdge)choice;
          TreeNode child = (edge.getChildRef() == NULL_REF || edge.isHyperEdge()) ? null : get(edge.getChildRef());

          deleteEdge(index);

          if ( child != null )
          {
            child.freeFromAncestor(this, null);
          }
        }
      }
      else
      {
        assert(choice instanceof ForwardDeadReckonLegalMoveInfo);
      }
    }

    freeChildren();

    leastLikelyWinner = -1;
    mostLikelyWinner = -1;
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
          long cr = ((TreeEdge)choice).getChildRef();
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
            long cr = edge.getChildRef();
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

              //  We just traverse the regular tree looking for nodes to free, so
              //  stop when we reach the start of the hyper edges
              if(edge.isHyperEdge())
              {
                break;
              }

              long cr = edge.getChildRef();
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

      return get(selectedEdge.getChildRef()).selectLeastLikelyExpandedNode(selectedEdge);
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
    assert(edge.getChildRef() == NULL_REF);

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

    createChildNodeForEdgeWithAssertedState(edge, newState, 0, isPseudoNullMove);
  }

  /**
   * @param edge
   * @param newState
   * @param extraDepthIncrement
   * @param isPseudoNullMove
   */
  void createChildNodeForEdgeWithAssertedState(TreeEdge edge, ForwardDeadReckonInternalMachineState newState, int extraDepthIncrement, boolean isPseudoNullMove)
  {
    TreeNode newChild = tree.allocateNode(newState, this, isPseudoNullMove);
    int roleIndex = (decidingRoleIndex + 1) % tree.numRoles;

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

    //  If this was a transposition to an existing node it can be linked at multiple depths.
    //  Give it the lowest depth at which it has been seen, as this is guaranteed to preserve
    //  correct implicit semantics of unexpanded children (assertion of non-terminality if below
    //  min game length depth)
    int expectedDepth;
    if ( tree.removeNonDecisionNodes && mNumChildren > 1 )
    {
      expectedDepth = ((depth/tree.numRoles + 1)*tree.numRoles + (newChild.decidingRoleIndex+1)%tree.numRoles) + extraDepthIncrement;
    }
    else
    {
      expectedDepth = depth + 1 + extraDepthIncrement;
    }
    if ( newChild.depth < 0 || newChild.depth > expectedDepth )
    {
      newChild.depth = (short)expectedDepth;
    }

    assert(newChild.depth%tree.numRoles == (newChild.decidingRoleIndex+1)%tree.numRoles);

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

              if ( edge.getChildRef() != NULL_REF && get(edge.getChildRef()) == current )
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

    TreeNode result = expandInternal(fullPathTo, jointPartialMove, parentDepth, false, false);

    assert(!tree.removeNonDecisionNodes || result.mNumChildren > 1 || result == tree.root || result.complete);
    assert(result.linkageValid());
    return result;
  }

  public void hyperExpand(TreePath fullPathTo, ForwardDeadReckonLegalMoveInfo[] jointPartialMove, short hyperDepth)
  {
    int roleIndex = (decidingRoleIndex+1)%tree.numRoles;

    //  Uncomment this if paranoid, but at some execution cost when asserts are enabled
    //assert(tree.validateForcedMoveProps(state, jointPartialMove));

    //  Perform hyper-expansion and add in hyper-edges
    //  This involves checking which children still have the same player with control
    //  (so only applies to non-simultaneous move games) and recursively expanding
    //  them, and then inserting 'hyper-edges' to the descendants where this player
    //  in NOT in control (i.e. - to all states reachable without any opponent
    //  choice).  Because this is recursive some car is needed with the singleton
    //  buffers in the MCTSTree instance used for interim results (to avoid memory allocation)
    //  We piggyback on the processing done by no-choice node removal, which guarantees
    //  that each child has moved to a different state (except in a special-case of root handling),
    //  and so only enable hyper-edge analysis if no-choice removal is active
    if (tree.removeNonDecisionNodes && tree.mRoleControlProps != null && fullPathTo != null && hyperDepth > hyperExpansionDepth)
    {
      boolean hyperExpansionNeeded = false;

      assert(state.contains(tree.mRoleControlProps[roleIndex]));

      //  First create child nodes for all with the same player in control.  This node creation
      //  is anyway implied by the need to recursively expand such children.
      //  TODO - we can optimize this by flagging the children that are same-control in expansion
      //  so that we don't have to create all children here
      if ( hyperExpansionDepth == 0 )
      {
        for (short lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
        {
          if (primaryChoiceMapping == null || primaryChoiceMapping[lMoveIndex] == lMoveIndex)
          {
            //  Create if necessary
            TreeNode child = createChildIfNeccessary(lMoveIndex, jointPartialMove, roleIndex);

            if ( child != null && child.state.contains(tree.mRoleControlProps[roleIndex]) )
            {
              hyperExpansionNeeded = true;
              break;
            }
          }
        }
      }
      else
      {
        Object lastChild = children[mNumChildren-1];

        hyperExpansionNeeded = (lastChild == null || (lastChild instanceof TreeEdge && ((TreeEdge)lastChild).isHyperEdge()));
      }

      //  Now perform the recursive expansion if any is needed
      if ( hyperExpansionNeeded )
      {
        for (short lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
        {
          if (primaryChoiceMapping == null || primaryChoiceMapping[lMoveIndex] == lMoveIndex)
          {
            Object choice = children[lMoveIndex];
            if ( choice instanceof TreeEdge &&  ((TreeEdge)choice).isHyperEdge() )
            {
              //  We're only interested in recursively expanding the regular tree linkage
              //  so as soon as we hit a hyper-edge we can stop
              break;
            }

            TreeNode child = getChild(lMoveIndex);

            if ( child != null && !child.complete && child.state.contains(tree.mRoleControlProps[roleIndex]) )
            {
              assert(choice instanceof TreeEdge);
              TreeEdge edge = (TreeEdge)choice;

              TreeNode expandedChild;

              assert(validateHyperChain(edge));

              if ( child.isUnexpanded() )
              {
                //  expand recursively
                fullPathTo.push(this, edge);

                expandedChild = child.expandInternal(fullPathTo, jointPartialMove, depth, false, false);

                fullPathTo.pop();

                //  Expanding can result in the discovery that forced moves transpose to the same state as another
                //  edge, in which case expansion can retire this edge - check before continuing
                if ( edge != children[lMoveIndex] )
                {
                  assert(primaryChoiceMapping != null && primaryChoiceMapping[lMoveIndex] != lMoveIndex);
                  continue;
                }

                //  It can also lead to a state where control has changed hands, so we don't actually have
                //  a valid hyper-path
                if ( !expandedChild.state.contains(tree.mRoleControlProps[roleIndex]))
                {
                  //  Must reset the joint moved forced props, as the expansion will have
                  //  disturbed them
                  tree.setForcedMoveProps(state, jointPartialMove);
                  continue;
                }
              }
              else
              {
                expandedChild = child;
              }
              assert(validateHyperChain(edge));

              expandedChild.hyperExpand(fullPathTo, jointPartialMove, (short)(hyperDepth-1));

              if ( !expandedChild.complete )
              {
                boolean expandedChildHasHyperEdges = false;
                Object lastChoice = expandedChild.children[expandedChild.mNumChildren-1];

                if ( (lastChoice instanceof TreeEdge) && ((TreeEdge)lastChoice).hyperSuccessor != null )
                {
                  expandedChildHasHyperEdges = true;
                }

                //  Mark this edge as unselectable
                edge.setIsSelectable(false);

                //  Add in the implied hyper-edges
                if ( expandedChildHasHyperEdges )
                {
                  for(short index = 0; index < expandedChild.mNumChildren; index++)
                  {
                    Object childChoice = expandedChild.children[index];
                    if ( childChoice instanceof TreeEdge && !((TreeEdge)childChoice).isSelectable())
                    {
                      continue;
                    }

                    if ( childChoice != null && (expandedChild.primaryChoiceMapping == null || expandedChild.primaryChoiceMapping[index] == index))
                    {
                      //  Children will have already been expanded by the recursive call above usually, but in
                      //  the case of a transposition it is possible they will not have been
                      TreeNode descendant = expandedChild.createChildIfNeccessary(index, jointPartialMove, roleIndex);

                      //  In the case of a hyper-edge who terminus no longer exists (because an intermediary step has been completed usually)
                      //  we may have no descendant, which means it's a dead hyper-edge and we should ignore it
                      if ( descendant == null )
                      {
                        continue;
                      }

                      //  In principal hyper-edges should provide direct access to all possible successor states which the
                      //  currently choosing player can force arrival at (those where the opponent gets the next choice)
                      //  In practice this leads to too much of a combinatoric explosion in the branching factor, so instead
                      //  we link to the terminal nodes of the sequences wherein the choosing player retains control.  This provides
                      //  most of the selection power of the theoretical approach with much less of an increase in branching factor
                      if ( !descendant.state.contains(tree.mRoleControlProps[roleIndex]) )
                      {
                        assert(!((TreeEdge)expandedChild.children[index]).isHyperEdge());
                        continue;
                      }
                      assert(((TreeEdge)expandedChild.children[index]).isHyperEdge());

                      //  Only need to add if we don't already have a (hyper or otherwise) child
                      //  which is this node
                      boolean alreadyPresent = false;

                      for(short ourIndex = 0; ourIndex < mNumChildren; ourIndex++)
                      {
                        TreeNode ourChild = getChild(ourIndex);
                        if ( ourChild != null && ourChild.state.equals(descendant.state))
                        {
                          alreadyPresent = true;
                          break;
                        }
                      }

                      if ( !alreadyPresent )
                      {
                        //  Add the new hyper-edge
                        TreeEdge descendantEdge = (TreeEdge)expandedChild.children[index];

                        assert(descendantEdge.mParentRef == expandedChild.getRef());

                        //  Do we need to expand the children array?
                        if ( children.length == mNumChildren )
                        {
                          Object[] newChildren = new Object[mNumChildren*2];

                          System.arraycopy(children, 0, newChildren, 0, mNumChildren);

                          children = newChildren;

                          if ( primaryChoiceMapping != null )
                          {
                            short[] newPrimaryChoiceMapping = new short[mNumChildren*2];

                            System.arraycopy(primaryChoiceMapping, 0, newPrimaryChoiceMapping, 0, mNumChildren);

                            primaryChoiceMapping = newPrimaryChoiceMapping;
                          }
                        }

                        TreeEdge hyperEdge = tree.edgePool.allocate(tree.mTreeEdgeAllocator);
                        hyperEdge.setParent(this, edge.mPartialMove);
                        hyperEdge.hyperSuccessor = descendantEdge;
                        hyperEdge.setIsHyperEdge(true);
                        //  Because edges are pooled and can be reallocated aftr being freed by an un-expansion
                        //  it is possible that the hyperSuccessor chain can contain stale edges that have been
                        //  reused.  So that we can validate chain integrity we therefore store the ref of the next
                        //  node in the chain, so that at each link the expected next node ref can be validated against
                        //  the succesor's parent ref
                        hyperEdge.nextHyperChild = expandedChild.getRef();
                        hyperEdge.setChildRef(descendantEdge.getChildRef());

                        if ( primaryChoiceMapping != null )
                        {
                          primaryChoiceMapping[mNumChildren] = mNumChildren;
                        }
                        children[mNumChildren++] = hyperEdge;

                        assert(validateHyperChain(hyperEdge));
                      }
                    }
                  }
                }
                else
                {
                  //  Do we need to expand the children array?
                  if ( children.length == mNumChildren )
                  {
                    Object[] newChildren = new Object[mNumChildren*2];

                    System.arraycopy(children, 0, newChildren, 0, mNumChildren);

                    children = newChildren;

                    if ( primaryChoiceMapping != null )
                    {
                      short[] newPrimaryChoiceMapping = new short[mNumChildren*2];

                      System.arraycopy(primaryChoiceMapping, 0, newPrimaryChoiceMapping, 0, mNumChildren);

                      primaryChoiceMapping = newPrimaryChoiceMapping;
                    }
                  }

                  //  Hyper link directly to the expanded child
                  TreeEdge hyperEdge = tree.edgePool.allocate(tree.mTreeEdgeAllocator);
                  hyperEdge.setParent(this, edge.mPartialMove);
                  hyperEdge.hyperSuccessor = null;
                  hyperEdge.setIsHyperEdge(true);
                  //  Because edges are pooled and can be reallocated aftr being freed by an un-expansion
                  //  it is possible that the hyperSuccessor chain can contain stale edges that have been
                  //  reused.  So that we can validate chain integrity we therefore store the ref of the next
                  //  node in the chain, so that at each link the expected next node ref can be validated against
                  //  the succesor's parent ref
                  hyperEdge.nextHyperChild = expandedChild.getRef();
                  hyperEdge.setChildRef(edge.getChildRef());
                  //  Can safely take on the visit count of the replaced normal edge
                  hyperEdge.setNumVisits(edge.getNumChildVisits());

                  if ( primaryChoiceMapping != null )
                  {
                    primaryChoiceMapping[mNumChildren] = mNumChildren;
                  }
                  children[mNumChildren++] = hyperEdge;

                  assert(validateHyperChain(hyperEdge));
                }

                assert(linkageValid());
              }
            }
          }
        }

        if ( mNumChildren > tree.maxChildrenSeen )
        {
          tree.maxChildrenSeen = mNumChildren;

          LOGGER.info("Max children for one node seen: " + tree.maxChildrenSeen);
        }
      }

      hyperExpansionDepth = hyperDepth;
    }
  }

  private TreeNode expandInternal(TreePath fullPathTo, ForwardDeadReckonLegalMoveInfo[] jointPartialMove, int parentDepth, boolean isRecursiveExpansion, boolean stateChangedInForcedExpansion)
  {
    TreePathElement pathTo = (fullPathTo == null ? null : fullPathTo.getTailElement());

    assert(this == tree.root || parents.size() > 0);
    assert(depth/tree.numRoles == tree.root.depth/tree.numRoles || (!tree.removeNonDecisionNodes && decidingRoleIndex != tree.numRoles-1) || (pathTo != null && pathTo.getEdgeUnsafe().mPartialMove.isPseudoNoOp) || tree.findTransposition(state) == this);
    //assert(state.size()==10);
    //boolean assertTerminal = !state.toString().contains("b");
    //  Find the role this node is choosing for
    int roleIndex = (decidingRoleIndex + 1) % tree.numRoles;

    //  Don't bother evaluating terminality of children above the earliest completion depth
    boolean evaluateTerminalOnNodeCreation = (tree.evaluateTerminalOnNodeCreation && (depth >= tree.gameCharacteristics.getEarliestCompletionDepth() || tree.heuristic.isEnabled()));

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

      if ( !isRecursiveExpansion && pathTo != null && pathTo.getEdgeUnsafe().getHasBeenTrimmed() )
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
      assert(mNumChildren > 0) : "Filtered move list for node was empty";
      Iterator<ForwardDeadReckonLegalMoveInfo> itr;

      if ( mNumChildren == 1 && this != tree.root && tree.removeNonDecisionNodes )
      {
        assert(pathTo != null);

        TreeNode parent = pathTo.getParentNode();
        TreeEdge edge = pathTo.getEdgeUnsafe();

        assert(parent != null);
        assert(parent.linkageValid());
        assert(edge.getChildRef() == getRef());

        itr = moves.getContents(choosingRole).iterator();

        //  Forced responses do not get their own nodes - we just re-purpose this one
        ForwardDeadReckonLegalMoveInfo forcedChoice = tree.searchFilter.nextFilteredMove(itr);
        ForwardDeadReckonInternalMachineState newState = tree.mChildStatesBuffer[0];
        TreeNode result = this;

        jointPartialMove[roleIndex] = forcedChoice;

        if (roleIndex == tree.numRoles - 1)
        {
           stateChangedInForcedExpansion = true;

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
            assert(edge.getChildRef() != existing.getRef());
            assert(existing.linkageValid());

            //  Detach the edge from the old node we just transitioned out of
            edge.setChildRef(NULL_REF);

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
                  else if ( linkingEdge.getChildRef() == existing.getRef() )
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

              edge.reset();
              tree.edgePool.free(edge);
              edge = (TreeEdge)parent.children[otherPathIndex];

              //  The old edge will have been selected through (else we wouldn't be expanding it)
              //  which will have incremented its visit count - need to do that on the replacement
              //  path to keep things correctly counted
              edge.incrementNumVisits();

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
              pathTo.set(parent, edge);
              assert(existing.numVisits-- > 0);
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

            //  Also there is the case where the edge has a heuristic weight bu the transposed to node did not
            //  which can lead to a lower visit count on the node.  To keep things in order we just increase
            //  it as necessary
            if ( existing.numVisits < edge.getNumChildVisits() )
            {
              existing.numVisits = edge.getNumChildVisits() - 1;
            }

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
              assert(this != tree.root);
              assert(mNumChildren == 1) : "Expansion of non-decision node occuring on apparent decision node!";
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
              result = existing.expandInternal(fullPathTo, jointPartialMove, parentDepth, true, stateChangedInForcedExpansion);
              assert(result.mNumChildren > 1 || result == tree.root || result.complete);
              assert(result.linkageValid());
            }
            else
            {
              //  Need to check if this is a heuristic sequence exit in this path, but wasn't in the path
              //  the node was originally created via
              if ( !existing.complete && stateChangedInForcedExpansion &&
                   (existing.heuristicWeight == 0 || Math.abs(existing.heuristicValue-50) < EPSILON) )
              {
                TreeEdge incomingEdge = pathTo.getEdgeUnsafe();

                if ( !incomingEdge.hasHeuristicDeviation() )
                {
                  tree.heuristic.getHeuristicValue( existing.state,
                                                    parent.state,
                                                    parent.state,
                                                    tree.mNodeHeuristicInfo);

                  if ( tree.mNodeHeuristicInfo.treatAsSequenceStep )
                  {
                    incomingEdge.setHasHeuristicDeviation(true);
                    existing.heuristicWeight = tree.mNodeHeuristicInfo.heuristicWeight;
                    existing.heuristicValue = tree.mNodeHeuristicInfo.heuristicValue[0];
                  }
                }
              }

              result = existing;
              assert(result.mNumChildren > 1 || result == tree.root || result.complete);
            }
            assert(result != this);
            assert(result != parent);
            assert(parent.complete || pathTo.getEdgeUnsafe().getChildRef() == result.getRef());
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
          assert(this != tree.root);
          assert(mNumChildren == 1) : "Expansion of non-decision node occuring on apparent decision node!";
          mNumChildren = 0;
          depth++;
          decidingRoleIndex = (decidingRoleIndex+1)%tree.numRoles;
          assert(depth%tree.numRoles == (decidingRoleIndex+1)%tree.numRoles);

          //  Recurse
          result = expandInternal(fullPathTo, jointPartialMove, parentDepth, true, stateChangedInForcedExpansion);
          assert(result.linkageValid());
        }

        assert(result.mNumChildren > 1 || result == tree.root || result.complete);
        assert(parent.linkageValid());
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
          TreeEdge edge = pathTo.getEdgeUnsafe();

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

                if ( !siblingEdge.hasHeuristicDeviation() )
                {
                  TreeNode siblingNode = get(siblingEdge.getChildRef());

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
        //  approximation would break down in games where:
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
                 ((tree.removeNonDecisionNodes || roleIndex == tree.numRoles - 1) &&
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

      if (evaluateTerminalOnNodeCreation && ((mNumChildren != 1 && tree.removeNonDecisionNodes) || roleIndex == tree.numRoles - 1))
      {
        for (short lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
        {
          if (primaryChoiceMapping == null || primaryChoiceMapping[lMoveIndex] == lMoveIndex)
          {
            StateInfo info = calculateTerminalityAndAutoExpansion(tree.mChildStatesBuffer[lMoveIndex]);
            boolean stateFastForwarded = false;
            int fastForwardDepthIncrement = 0;

            if ( mNumChildren != 1 && tree.removeNonDecisionNodes )
            {
              //  Piggy-back on the fact that we have to run the state anyway to fast-forward
              //  forced move expansions to their terminus (a choice node or a terminal node)
              //  In particular note that we always evaluate terminal on creation for games with heuristics
              //  so this fast-forwarding will always occur in such games
              while ( !info.isTerminal && tree.setForcedMoveProps(tree.mChildStatesBuffer[lMoveIndex], tree.mFastForwardPartialMoveBuffer) )
              {
                fastForwardDepthIncrement += tree.numRoles;
                stateFastForwarded = true;
                tree.underlyingStateMachine.getNextState(tree.mChildStatesBuffer[lMoveIndex],
                                                         tree.factor,
                                                         tree.mFastForwardPartialMoveBuffer,
                                                         tree.mStateScratchBuffer);

                //  In a factorized game we need to normalize the generated state
                //  so as to not fall foul of potential corruption of the non-factor
                //  element engendered by not making a move in other factors
                if ( tree.factor != null )
                {
                  tree.makeFactorState(tree.mStateScratchBuffer);
                }

                tree.mChildStatesBuffer[lMoveIndex].copy(tree.mStateScratchBuffer);
                info = calculateTerminalityAndAutoExpansion(tree.mChildStatesBuffer[lMoveIndex]);
              }

              if ( stateFastForwarded )
              {
                short remappedTo = -1;

                //  Since we have changed the state fixup the primary choice mappings
                for (short i = 0; i < lMoveIndex; i++)
                {
                  if (children[i] != null && tree.mChildStatesBuffer[i].equals(tree.mChildStatesBuffer[lMoveIndex]))
                  {
                    if ( primaryChoiceMapping == null )
                    {
                      primaryChoiceMapping = new short[mNumChildren];
                      for(short j = 0; j < mNumChildren; j++)
                      {
                        primaryChoiceMapping[j] = j;
                      }
                    }
                    primaryChoiceMapping[lMoveIndex] = i;
                    remappedTo = i;
                    break;
                  }
                }
                if ( remappedTo != -1 )
                {
                  for(short j = (short)(lMoveIndex+1); j < mNumChildren; j++)
                  {
                    if ( primaryChoiceMapping[j] == lMoveIndex )
                    {
                      primaryChoiceMapping[j] = remappedTo;
                    }
                  }
                }
              }
            }

            //  We need to create the node at once is a fast-forward has taken place since
            //  the state will not be that reached directly by the move choice and we will
            //  not have access to the correct state information in other contexts
            if ( (primaryChoiceMapping == null || primaryChoiceMapping[lMoveIndex] == lMoveIndex) &&
                 (info.isTerminal || info.autoExpand  || stateFastForwarded) )
            {
              TreeEdge newEdge = tree.edgePool.allocate(tree.mTreeEdgeAllocator);
              newEdge.setParent(this, (ForwardDeadReckonLegalMoveInfo)children[lMoveIndex]);
              children[lMoveIndex] = newEdge;
              jointPartialMove[roleIndex] = newEdge.mPartialMove;
              createChildNodeForEdgeWithAssertedState(newEdge, tree.mChildStatesBuffer[lMoveIndex], fastForwardDepthIncrement, false);

              TreeNode newChild = get(newEdge.getChildRef());
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

              assert(newChild.linkageValid());
            }
          }
        }
      }

      assert(linkageValid());

      if (MCTSTree.USE_STATE_SIMILARITY_IN_EXPANSION && topMoveWeight > 0)
      {
        for (short lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
        {
          if ((primaryChoiceMapping == null || primaryChoiceMapping[lMoveIndex] == lMoveIndex) )
          {
            Object choice = children[lMoveIndex];
            TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
            if (edge == null || !get(edge.getChildRef()).isTerminal)
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

      //calculatePathMoveWeights(fullPathTo);

      if ( tree.heuristic.isEnabled() && ((mNumChildren != 1 && tree.removeNonDecisionNodes) || roleIndex == tree.numRoles - 1) )
      {
        // Determine the appropriate reference node to evaluate children with respect to
        // Evaluate wrt the first ancestor state with a reasonable number of visits which
        // is not itself immediately preceded by a heuristic exchange
        boolean previousEdgeHadHeuristicDeviation = false;
        TreeNode referenceNode = tree.root;

        //  If the state represented by this node has changed due to forced move collapsing
        //  there may have been a heuristic change, which needs to be noted on the incoming edge
        if ( pathTo != null )
        {
          TreeNode parent = pathTo.getParentNode();

          if ( stateChangedInForcedExpansion || parent.depth/tree.numRoles < depth/tree.numRoles - 1 )
          {
            TreeEdge incomingEdge = pathTo.getEdgeUnsafe();

            if ( !incomingEdge.hasHeuristicDeviation() )
            {
              tree.heuristic.getHeuristicValue( state,
                                                parent.state,
                                                parent.state,
                                                tree.mNodeHeuristicInfo);

              if ( tree.mNodeHeuristicInfo.treatAsSequenceStep )
              {
                incomingEdge.setHasHeuristicDeviation(true);
              }
            }
          }
        }

        if ( fullPathTo != null )
        {
          boolean previousEdgeWalked = false;
          TreePathElement pathElement = null;
          boolean traversedHeuristicSequence = false;
          boolean inHeuristicSequence = false;

          while(fullPathTo.hasMore())
          {
            fullPathTo.getNextNode();
            pathElement = fullPathTo.getCurrentElement();

            assert(pathElement != null);

            boolean pathElementHasHeuristicDeviation = pathElement.getEdgeUnsafe().hasHeuristicDeviation();

            if ( !previousEdgeWalked )
            {
              previousEdgeHadHeuristicDeviation = pathElementHasHeuristicDeviation;
              previousEdgeWalked = true;
            }

            if ( pathElementHasHeuristicDeviation && !inHeuristicSequence && traversedHeuristicSequence )
            {
              break;
            }

            if ( pathElement.getParentNode().numUpdates > 200 && !pathElementHasHeuristicDeviation )
            {
              break;
            }

            inHeuristicSequence = pathElementHasHeuristicDeviation;
            traversedHeuristicSequence |= inHeuristicSequence;
            referenceNode = pathElement.getParentNode();
          }

          //  Restore path cursor so that next attempt to enumerate is clean
          fullPathTo.resetCursor();
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

              edge.setHasHeuristicDeviation(true);
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

                if ( edge.getChildRef() == NULL_REF )
                {
                  createChildNodeForEdge(edge, jointPartialMove);

                  assert(linkageValid());

                  assert(!evaluateTerminalOnNodeCreation || !calculateTerminalityAndAutoExpansion(get(edge.getChildRef()).state).isTerminal);

                  assert(linkageValid());
                }

                TreeNode newChild = get(edge.getChildRef());

                if (!newChild.isTerminal && (newChild.numVisits == 0 || newChild.heuristicWeight == 0 || Math.abs(newChild.heuristicValue-50) < EPSILON))
                {
                  newChild.heuristicValue = tree.mNodeHeuristicInfo.heuristicValue[0];
                  newChild.heuristicWeight = heuristicWeightToApply;

                  //  If this turns out to be a transition into an already visited child
                  //  then do not apply the heuristic seeding to the average scores
                  if (newChild.numVisits == 0)
                  {
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
                  }

                  // Use the heuristic confidence to guide how many virtual rollouts to pretend there have been through
                  // the new child.
                  newChild.numUpdates += tree.mNodeHeuristicInfo.heuristicWeight;
                  assert(!Double.isNaN(newChild.getAverageScore(0)));

                  newChild.numVisits += tree.mNodeHeuristicInfo.heuristicWeight;
                  edge.setNumVisits(newChild.numVisits);
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
            long cr = ((TreeEdge)choice).getChildRef();
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
          if (edge != null && edge.getChildRef() != NULL_REF)
          {
            total += get(edge.getChildRef()).getAverageScore(0) * edge.getNumChildVisits();
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
    TreeNode childNode = get(edge.getChildRef());
    int effectiveNumChildVisits = edge.getNumChildVisits() + 1;

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

    if (tree.USE_UCB_TUNED)
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
      result = getExplorationBias(edge) *
          Math.sqrt(Math.min(0.25, varianceBound) * lUcbExploration) / tree.roleRationality[roleIndex];
    }
    else
    {
      result = getExplorationBias(edge) *
          Math.sqrt(lUcbExploration) / tree.roleRationality[roleIndex];
    }

    result *= (1 + edge.explorationAmplifier)*(edge.isHyperEdge() ? 5 : 1);
    return result;
  }

  private double getAverageCousinMoveValue(TreeEdge relativeTo, int roleIndex)
  {
    TreeNode lNode = get(relativeTo.getChildRef());
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
            if (edge == null || edge.getChildRef() == NULL_REF)
            {
              continue;
            }

            TreeNode child = get(edge.getChildRef());
            if (child != null)
            {
              for (short nephewIndex = 0; nephewIndex < child.mNumChildren; nephewIndex++)
              {
                Object rawChoice = child.children[nephewIndex];
                Object nephewChoice = child.children[child.primaryChoiceMapping == null ? nephewIndex : child.primaryChoiceMapping[nephewIndex]];

                TreeEdge nephewEdge = (nephewChoice instanceof TreeEdge ? (TreeEdge)nephewChoice : null);
                if (nephewEdge == null || nephewEdge.getChildRef() == NULL_REF)
                {
                  continue;
                }

                TreeNode nephew = get(nephewEdge.getChildRef());
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
        parents.get(0).dumpTree("subTree.txt");
        LOGGER.warn("No newphews found for search move including own child!");
        tree.cousinMovesCachedFor = NULL_REF;
      }
      //getAverageCousinMoveValue(relativeTo);
      return lNode.getAverageScore(roleIndex);
    }
    return accumulatedMoveInfo.averageScores[roleIndex];
  }

  private double getExplorationBias(TreeEdge moveEdge)
  {
    double result = tree.gameCharacteristics.getExplorationBias();

//    if ( moveEdge.moveWeight != 0 )
//    {
//      result *= (1 + moveEdge.moveWeight/25);
//    }

    //result *= (tree.DEPENDENCY_HEURISTIC_STRENGTH*moveEdge.moveWeight + 0.5);
    return result;
  }

  private double sigma(double x)
  {
    return 1/(1+Math.exp(-x));
  }

  private double heuristicUCT(TreeEdge moveEdge)
  {
    return 0;
  }

  private double exploitationUCT(TreeEdge inboundEdge, int roleIndex)
  {
    //  Force selection of a pseudo-noop as an immediate child of the
    //  root as much as the best scoring node as there is a 50-50 chance we'll need to pass
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
          if (edge2 != null && edge2.getChildRef() != NULL_REF)
          {
            TreeNode lChild = get(edge2.getChildRef());
            if (lChild.getAverageScore(roleIndex) > bestChildScore)
            {
              bestChildScore = lChild.getAverageScore(roleIndex);
            }
          }
        }
      }

      return bestChildScore / 100;
    }

    TreeNode lInboundChild = get(inboundEdge.getChildRef());
    if (tree.gameCharacteristics.isSimultaneousMove)
    {
      if (roleIndex == 0)
      {
        return lInboundChild.getAverageScore(roleIndex) / 100;
      }
      return getAverageCousinMoveValue(inboundEdge, roleIndex)/100;
    }

    double result = lInboundChild.getAverageScore(roleIndex) / 100;// + heuristicValue()/Math.log(numVisits+2);// + averageSquaredScore/20000;

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
  private double unexpandedChildUCTValue(int roleIndex, TreeEdge edge)
  {
    if ( tree.useEstimatedValueForUnplayedNodes )
    {
      // Extract the common parts of the calculation to avoid making expensive calls twice.
      double lCommon = 2 * Math.log(numVisits + 1);

      double varianceBound = Math.sqrt(lCommon);
      double explorationTerm = tree.gameCharacteristics.getExplorationBias() *
             Math.sqrt(Math.min(0.5, varianceBound) * lCommon);

      if ( edge != null )
      {
        explorationTerm *= (1 + edge.explorationAmplifier);
        explorationTerm += heuristicUCT(edge);
      }

      return explorationTerm + getAverageScore(roleIndex)/100 + tree.r.nextDouble() * EPSILON;
    }

    //  Else use standard MCTS very high values for unplayed
    return 1000 + tree.r.nextDouble() * EPSILON + (edge == null ? 0 : edge.explorationAmplifier);
  }

  private void normalizeScores(boolean bTrace)
  {
    double weightTotal = 0;
    int choosingRoleIndex = (decidingRoleIndex+1)%tree.numRoles;

    for(int role = 0; role < tree.numRoles; role++)
    {
      tree.mNodeAverageScores[role] = 0;
      tree.mNodeAverageSquaredScores[role] = 0;
    }

    //  Complete children are a problem since every would-be visit through
    //  a complete child actually results in a visit through a non-complete
    //  alternate selection, with override scores from the complete node
    //  being applied during update.  The effect is that the non-completed nodes
    //  will have exaggerated visit counts relative to their UCT selection.
    //  We have insufficient information to fully correct for this, so we approximate
    //  by assuming that the visits to the complete nodes have also been redistributed
    //  in proportion to the visit counts of the other nodes
    int numCompleteVisits = 0;
    double highestScore = -Double.MAX_VALUE;
    double highestScoreWeight = 0;

    for(int i = 0; i < mNumChildren; i++)
    {
      Object choice = children[i];

      if ( choice instanceof TreeEdge )
      {
        TreeEdge edge = (TreeEdge)choice;

        if ( edge.getChildRef() != NULL_REF && edge.isSelectable() )
        {
          TreeNode child = get(edge.getChildRef());

          if ( child != null )
          {
            if ( child.complete )
            {
              numCompleteVisits += edge.getNumChildVisits();
            }

            double score = child.getAverageScore(choosingRoleIndex);
            if ( score > highestScore )
            {
              highestScore = score;
              highestScoreWeight = edge.getNumChildVisits();
            }
          }
        }
      }
    }

    double incompleteVisitProportion = ((double)numVisits - (double)numCompleteVisits)/numVisits;

    for(int i = 0; i < mNumChildren; i++)
    {
      Object choice = children[i];

      if ( choice instanceof TreeEdge )
      {
        TreeEdge edge = (TreeEdge)choice;

        if ( edge.getChildRef() != NULL_REF && edge.isSelectable() )
        {
          TreeNode child = get(edge.getChildRef());

          if ( child != null  )
          {
            //double effectiveVisits = (child.complete ? edge.getNumChildVisits() : incompleteVisitProportion*edge.getNumChildVisits());
            //double weight = effectiveVisits*Math.sqrt(effectiveVisits);
            double weight = highestScoreWeight/(1 + highestScoreWeight*(highestScore - child.getAverageScore(choosingRoleIndex))/(30*Math.log(numVisits)));

            if ( bTrace )
            {
              LOGGER.info("Move " + edge.descriptiveName() + "choosing score " + child.getAverageScore(choosingRoleIndex) + " [" + edge.getNumChildVisits() + "], weight: " + weight);
            }
            for(int role = 0; role < tree.numRoles; role++)
            {
              double score = child.getAverageScore(role);
              double squaredScore = child.getAverageSquaredScore(role);

              //  Normalize for any heuristic bias that would normally have been
              //  applied for propagations form this child to this parent
              if ( heuristicWeight > 0 )
              {
                double applicableValue = (heuristicValue > 50 ? heuristicValue : 100 - heuristicValue);

                if ( applicableValue > EPSILON )
                {
                  double rootSquaredScore = Math.sqrt(squaredScore);

                  if ((heuristicValue > 50) == (role == 0))
                  {
                    score = score + (100 - score) * (applicableValue - 50) / 50;
                    rootSquaredScore = rootSquaredScore + (100 - rootSquaredScore) * (applicableValue - 50) / 50;
                  }
                  else
                  {
                    score = score - (score) * (applicableValue - 50) / 50;
                    rootSquaredScore = rootSquaredScore - (rootSquaredScore) * (applicableValue - 50) / 50;
                  }

                  squaredScore = rootSquaredScore*rootSquaredScore;
                }
              }
              tree.mNodeAverageScores[role] += weight*score;
              tree.mNodeAverageSquaredScores[role] += weight*squaredScore;
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
    double bestValue = -Double.MAX_VALUE;

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
            if (!lEdge.mPartialMove.isPseudoNoOp && lEdge.mPartialMove.move.equals(xiForceMove))
            {
              selectedIndex = lii;
              break;
            }
          }
        }

        assert(selectedIndex != -1 || tree.factor != null) : "Failed to find forced move: " + xiForceMove;
      }

      if (selectedIndex == -1)
      {
        //calculatePathMoveWeights(path);

        if ( tree.USE_NODE_SCORE_NORMALIZATION && numVisits > 500 && (numVisits&0xff) == 0xff )
        {
          normalizeScores(false);
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

          //  Hyper-edges may become stale due to down-stream links being freed - ignore
          //  hyper paths with stale linkage
          if(edge.hyperSuccessor != null && edge.hyperLinkageStale())
          {
            deleteHyperEdge(mostLikelyWinner);
          }
          else
          {
            long cr = edge.getChildRef();

            if(cr != NULL_REF)
            {
              TreeNode c = get(cr);
              if (c != null && (!c.complete) && !c.allChildrenComplete)
              {
                double uctValue;

                if (c.numVisits == 0 && !c.complete)
                {
                  uctValue = unexpandedChildUCTValue(roleIndex, edge);
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
                             exploitationUCT(edge, roleIndex) +
                             heuristicUCT(edge);
                }

                if (uctValue >= mostLikelyRunnerUpValue)
                {
                  selectedIndex = mostLikelyWinner;
                }
              }
            }
          }
        }

        if (selectedIndex == -1)
        {
          //  Previous second best now preferred over previous best so we need
          //  to recalculate
          mostLikelyRunnerUpValue = Double.MIN_VALUE;

          boolean hyperLinksRemoved;

          do
          {
            hyperLinksRemoved = false;

            for (short i = 0; i < mNumChildren; i++)
            {
              //  Only select one move that is state-equivalent, and don't allow selection of a pseudo-noop
              if ( primaryChoiceMapping == null || primaryChoiceMapping[i] == i )
              {
                Object choice = children[i];

                if ( choice == null )
                {
                  //  Previously removed stale hyper-edge (slot)
                  continue;
                }

                TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
                double uctValue;
                long cr;
                TreeNode c;

                if (edge != null && (cr = edge.getChildRef()) != NULL_REF && (c = get(cr)) != null)
                {
                  //  In the presence of hyper edges some normal edges will not be selectable because
                  //  they are just sub-elements of selectable hyper-edges
                  if ( !edge.isSelectable() )
                  {
                    continue;
                  }

                  //  Hyper-edges may become stale due to down-stream links being freed - ignore
                  //  hyper paths with stale linkage
                  if(edge.hyperSuccessor != null && edge.hyperLinkageStale())
                  {
                    deleteHyperEdge(i);
                    hyperLinksRemoved = true;
                    continue;
                  }

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

                    if (c.numVisits == 0)
                    {
                      uctValue = unexpandedChildUCTValue(roleIndex, edge);
                    }
                    else
                    {
                      assert(edge.getNumChildVisits() <= c.numVisits || (edge.hyperSuccessor != null && c.complete));

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
                                 exploitationUCT(edge, roleIndex) +
                                 heuristicUCT(edge);
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
                  if ( edge != null && edge.hyperSuccessor != null )
                  {
                    //  Stale hyper-link - can be ignored now its target has gone
                    continue;
                  }

                  //  A null child ref in an extant edge is a not-yet selected through
                  //  path which is asserted to be non-terminal and unvisited
                  uctValue = unexpandedChildUCTValue(roleIndex, edge);

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
          } while(hyperLinksRemoved);
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

        assert(get(chosenEdge.getChildRef()) != null);
        assert(chosenEdge.mPartialMove.isPseudoNoOp || get(chosenEdge.getChildRef()).complete);
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

    if (selected.getChildRef() == NULL_REF)
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
             (depth < tree.gameCharacteristics.getEarliestCompletionDepth() && !tree.heuristic.isEnabled()) ||
             this == tree.root ||
             !calculateTerminalityAndAutoExpansion(get(selected.getChildRef()).state).isTerminal);
      assert(linkageValid());
    }

    assert(get(selected.getChildRef()) != null);

    if (!complete && (tree.removeNonDecisionNodes || roleIndex == 0) && MCTSTree.USE_STATE_SIMILARITY_IN_EXPANSION )
    {
      tree.mStateSimilarityMap.add(this);
    }

    final double explorationAmplifierDecayRate = 0.6;
    selected.explorationAmplifier *= explorationAmplifierDecayRate;
    TreePathElement result = null;
    //  If we selected a hyper-edge then we actually need to push all of its constituent elements
    //  so that the stats update applies to the correct intermediate states also
    if ( selected.hyperSuccessor == null )
    {
      assert(get(selected.getChildRef()).parents.contains(this));
      result = path.push(this, selected);
    }
    else
    {
      TreeNode intermediaryParent = this;

      while( selected.hyperSuccessor != null )
      {
        //  Find the principal edge for the next part of the hyper-edge's sub-path
        TreeEdge principalEdge = null;

        for(int lMoveIndex = 0; lMoveIndex < intermediaryParent.mNumChildren; lMoveIndex++)
        {
          Object intermediaryChoice = intermediaryParent.children[lMoveIndex];

          if ( intermediaryChoice instanceof TreeEdge )
          {
            TreeEdge candidateEdge = (TreeEdge)intermediaryChoice;

            if ( candidateEdge.mPartialMove.move == selected.mPartialMove.move )
            {
              assert(!candidateEdge.isSelectable());
              principalEdge = candidateEdge;
              break;
            }
          }
        }

        assert(principalEdge != null);

        result = path.push(intermediaryParent, principalEdge);
        principalEdge.incrementNumVisits();

        assert(principalEdge.getChildRef() == selected.nextHyperChild);
        TreeNode nextNode = get(principalEdge.getChildRef());

        assert(nextNode.parents.contains(intermediaryParent));
        intermediaryParent = nextNode;

        assert(intermediaryParent != null);

        //  It is possible that node completion has propagated 'part way' through
        //  a hyper-edge.  If so stop at the first complete node encountered when
        //  resolving the sub-path
        if ( intermediaryParent.complete )
        {
          break;
        }

        intermediaryParent.numVisits++;
        selected.incrementNumVisits();

        selected = selected.hyperSuccessor;
      }

      if ( !intermediaryParent.complete )
      {
        assert(get(selected.getChildRef()).parents.contains(intermediaryParent));
        result = path.push(intermediaryParent, selected);
      }
    }

    assert(result != null);

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
      assert(bestCompleteNode == get(bestSelectedEdge.getChildRef()));

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
        if (edge != null && get(edge.getChildRef()) != null)
        {
          TreeNode lNode = get(edge.getChildRef());
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

          if ( choice == null )
          {
            continue;
          }

          TreeEdge edge2 = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
          if ( edge2 != null )
          {
            if ( !edge2.isSelectable() )
            {
              continue;
            }
            if (edge2.getChildRef() != NULL_REF)
            {
              if ( get(edge2.getChildRef()) != null)
              {
                TreeNode lNode2 = get(edge2.getChildRef());
                String lLog = "    Response " +
                              edge2.mPartialMove.move + (edge2.isHyperEdge() ? " (hyper)" : "") +
                              " scores " + lNode2.stringizeScoreVector() +
                              ", visits " + lNode2.numVisits +
                              ", ref : " + lNode2.mRef +
                              (lNode2.complete ? " (complete)" : "");

                if (xiResponsesTraced < 400)
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

      if (edge2.getChildRef() != NULL_REF && get(edge2.getChildRef()) != null )
      {
        xiResponsesTraced = get(edge2.getChildRef()).traceFirstChoiceNode(xiResponsesTraced);
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

  private void dumpTree(PrintWriter writer, int dumpDepth, TreeEdge arrivalPath, int childIndex)
  {
    if (arrivalPath == null)
    {
      indentedPrint(writer, dumpDepth * 2, "Root scores " +
          stringizeScoreVector());
    }
    else
    {
      indentedPrint(writer,
                    dumpDepth * 2,
                    "@" +
                        dumpDepth +
                        ": Move " +
                        arrivalPath.descriptiveName() +
                        " [" + childIndex + "] D" + depth +
                        " (choosing role " + (decidingRoleIndex+1)%tree.numRoles + ")" +
                        " scores " + stringizeScoreVector() + "[" + heuristicValue + "@" + heuristicWeight + "] (ref " + mRef +
                        ") - visits: " + numVisits + " (" +
                        arrivalPath.getNumChildVisits() + ", " + arrivalPath.hasHeuristicDeviation() + "), updates: " + numUpdates);
    }

    if (sweepSeq == tree.sweepInstance)
    {
      indentedPrint(writer, (dumpDepth + 1) * 2, "...transition...");
    }
    else
    {
      sweepSeq = tree.sweepInstance;

      for (short index = 0; index < mNumChildren; index++)
      {
        if ( primaryChoiceMapping == null || primaryChoiceMapping[index] == index )
        {
          Object choice = children[index];
          if ( choice == null )
          {
            continue;
          }

          TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
          if (edge != null && edge.getChildRef() != NULL_REF && get(edge.getChildRef()) != null && edge.isSelectable())
          {
            get(edge.getChildRef()).dumpTree(writer, dumpDepth + 1, edge, index);
          }
          else if ( edge != null && !edge.isSelectable() )
          {
            indentedPrint(writer,
                          (dumpDepth+1) * 2,
                          "@" +
                              (dumpDepth + 1) +
                              ": Move " +
                              edge.mPartialMove.move +
                              " unselectable (" + edge.getNumChildVisits() + ")");
          }
          else
          {
            indentedPrint(writer,
                          (dumpDepth+1) * 2,
                          "@" +
                              (dumpDepth + 1) +
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
      File f = new File(TEMP_DIR, filename);
      PrintWriter writer = new PrintWriter(f);
      dumpTree(writer, 0, null, 0);
      writer.close();
    }
    catch (Exception e)
    {
      GamerLogger.logStackTrace("StateMachine", e);
    }
  }

  public FactorMoveChoiceInfo getBestMove(boolean traceResponses, StringBuffer pathTrace, boolean firstDecision)
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

    //  If were asked for the first actual decision drill down through any leading no-choice path
    if ( firstDecision && mNumChildren == 1 )
    {
      return get(((TreeEdge)children[0]).getChildRef()).getBestMove(traceResponses, pathTrace, firstDecision);
    }

    //  If there is no pseudo-noop then there cannot be any penalty for not taking
    //  this factor's results - we simply return a pseudo-noop penalty value of 0
    result.pseudoNoopValue = 100;

    // This routine is called recursively for path tracing purposes.  When
    // calling this routine for path tracing purposes, don't make any other
    // debugging output (because it would be confusing).
    boolean lRecursiveCall = (pathTrace != null);

    // Find the role which has a choice at this node.  If this function is
    // being called for real (rather than for debug trace) it MUST be our role
    // (always 0), otherwise why are we trying to get the best move?
    int roleIndex = (decidingRoleIndex + 1) % tree.numRoles;
    assert(lRecursiveCall || roleIndex == 0 || firstDecision);
    assert(mNumChildren != 0) : "Asked to get best move when there are NO CHILDREN!";

    int maxChildVisitCount = 1;
    if (!lRecursiveCall)
    {
      for (int lii = 0; lii < mNumChildren; lii++)
      {
        Object choice = children[lii];
        TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
        if (edge != null && edge.getChildRef() != NULL_REF)
        {
          TreeNode lNode = get(edge.getChildRef());

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

      if ( choice == null )
      {
        continue;
      }

      TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
      if (edge == null || edge.getChildRef() == NULL_REF)
      {
        if ( !lRecursiveCall && !firstDecision )
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

      if ( !edge.isSelectable() )
      {
        //  Superseded by hyper-edges
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
        TreeNode child = get(edge.getChildRef());

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
//        if ( firstDecision && (edge.mPartialMove.toString().contains("5 6 6 5")))
//        {
//          LOGGER.info("Force-selecting " + edge.mPartialMove);
//          bestNode = child;
//          bestScore = 99;
//          bestMoveScore = bestNode.getAverageScore(0);
//          bestEdge = edge;
//          break;
//        }
//        if ( firstDecision && (edge.mPartialMove.toString().contains("8 1 7 2") || edge.mPartialMove.toString().contains("8 1 7 2")))
//        {
//          LOGGER.info("Force-UNselecting " + edge.mPartialMove);
//          moveScore = 1;
//        }
        //	If we have complete nodes with equal scores choose the one with the highest variance
        if (child.complete)
        {
          if (moveScore < 0.1)
          {
            //  Prefer more distant losses to closer ones
            moveScore = (child.completionDepth - tree.mGameSearcher.getRootDepth()) - 500;
            assert(moveScore <= 0);
            //assert(moveScore >= -500);
          }

          //  A complete score is certain, but we're comparing within a set that has only
          //  has numVisits TOTAL visits so still down-weight by the same visit count the most
          //  selected child has.  This avoids a tendency to throw a marginal win away for a
          //  definite draw.  Especially in games with low signal to noise ratio (everything looks
          //  close to 50%) this can be important
          //  We add EPSILON to break ties with the most-selected (but incomplete) node in favour of
          //  the complete one.  If we don't do this rounding errors can lead to an indeterminate
          //  choice (between this and the most selected node)
          selectionScore = moveScore *
              (1 - 20 * Math.log(numVisits) /
                  (20 * Math.log(numVisits) + maxChildVisitCount)) + EPSILON;
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

            //  Whether we're looking for a choice of node to concentrate local search on (firstDecision==true)
            //  of looking for our final choice to play (firstDecision==false) impacts how we weight
            //  children relative to one another
            if ( firstDecision )
            {
              //  If it's already been local-searched down-weight it to avoid flipping
              //  back and forth between the same few moves
              if ( child.hasBeenLocalSearched )
              {
                selectionScore *= 0.95;
              }
            }
            else
            {
              //  Also down-weight nodes that have not been subject to local-search
              //  This helps cope with cases where the MCTS convergence tips over
              //  to a new node near the end of turn processing where the previous candidate
              //  had been confirmed to have no local loss, but the new choice turns out
              //  to actually be a loss.  The slight loss of potentially going for the second best
              //  MCTS choice when things are very close is more than compensated for by the
              //  added safety
              if ( !child.hasBeenLocalSearched )
              {
                selectionScore *= 0.95;
              }
            }

            //  If a move was found to be a local loss, but it's still incomplete (so global
            //  result is unknown) down-weight its selection significantly
            if ( child.isLocalLossFrom != null )
            {
              selectionScore /= 2;
            }
          }
        }
        if (!lRecursiveCall && !firstDecision)
        {
          LOGGER.info("Move " + edge.descriptiveName() +
                      " scores " + FORMAT_2DP.format(moveScore) + " (selectionScore score " +
                      FORMAT_2DP.format(selectionScore) + ", selection count " +
                      child.numVisits + ", ref " + child.mRef +
                      (child.complete ? (", complete [" + ((child.completionDepth - tree.root.depth)/tree.numRoles) + "]") : "") + ")");
        }

        if (child.mNumChildren != 0 && !child.complete && traceResponses && !firstDecision)
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

    if (!lRecursiveCall && !firstDecision)
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

    // Trace the most likely path through the tree if searching from the root
    if ( !firstDecision )
    {
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
        bestNode.getBestMove(false, pathTrace, false);
      }
      else
      {
        LOGGER.info(pathTrace.toString());
      }
    }

    if (bestEdge == null)
    {
      //  This can happen if the node has no expanded children
      assert(this != tree.root || complete) : "Root incomplete but has no expanded children";

      //  If we're being asked for the first decision node we need to give a response even if it's
      //  essentially arbitrary from equal choices
      if ( firstDecision )
      {
        //  If nothing is expanded pick the first (arbitrarily)
        Object firstChoice = children[0];
        result.bestEdge = null;
        result.bestMove = (firstChoice instanceof ForwardDeadReckonLegalMoveInfo) ? (ForwardDeadReckonLegalMoveInfo)firstChoice : ((TreeEdge)firstChoice).mPartialMove;
        //  Complete with no expanded children implies arbitrary child must match parent score
        result.bestMoveValue = getAverageScore(0);
        result.resultingState = null;
      }
      else
      {
        //  For a non firstDecision call (i.e. - actually retrieving best move to play)
        //  we need to ensure that we return null here so that in a factorized game this
        //  factor will never be picked
        result.bestMove = null;
      }
    }
    else
    {
      ForwardDeadReckonLegalMoveInfo moveInfo = bestEdge.mPartialMove;

      result.bestEdge = bestEdge;
      result.bestMove = moveInfo;
      result.resultingState = get(bestEdge.getChildRef()).state;
      if (!moveInfo.isPseudoNoOp)
      {
        result.bestMoveValue = bestMoveScore;
        result.bestMoveIsComplete = get(bestEdge.getChildRef()).complete;
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
                                       1);
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

      TreeNode updateFromNode;

      if ( lRequest.mComplete )
      {
        //  Propagate the implication sof the completion discovered by the playout
        markComplete(lRequest.mAverageScores, (short)(getDepth()+1));
        tree.processNodeCompletions();
        lRequest.mPath.trimToCompleteLeaf();
        //  Trim down the update path so that we start updating only from the
        //  first completed node as several trailing elements may be complete
        updateFromNode = lRequest.mPath.getTailElement().getChildNode();
      }
      else
      {
        updateFromNode = this;
      }

      long lBackPropTime;
      //if ( lRequest.mPath.isValid() )
      {
        lBackPropTime = updateFromNode.updateStats(lRequest.mAverageScores,
                                         lRequest.mAverageSquaredScores,
                                         lRequest.mPath,
                                         lRequest.mWeight);
      }
//      else
//      {
//        lBackPropTime = 0;
//      }
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
   *
   * @return the time taken to do the update, in nanoseconds
   */
  public long updateStats(double[] xiValues,
                          double[] xiSquaredValues,
                          TreePath xiPath,
                          double  xiWeight)
  {
    long lStartTime = System.nanoTime();
    assert(checkFixedSum(xiValues));

    boolean lastNodeWasComplete = false;
    TreeNode lNextNode;
    for (TreeNode lNode = this; lNode != null; lNode = lNextNode)
    {
      TreePathElement lElement = xiPath.getCurrentElement();
      TreeEdge lChildEdge = (lElement == null ? null : lElement.getEdgeUnsafe());
      lNextNode = null;

      if ( lNode.heuristicWeight > 0 && !lastNodeWasComplete )
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

      //  For a non-decisive losing complete node we know this path will not actually be chosen
      //  so reduce its weight significantly.  This helps a lot in games like breakthrough where
      //  all responses but one (to an enemy pawn advancing the 7th/2nd rank) lose, by preventing
      //  the necessary expansion of every child resulting in a very misleading value for the immediate
      //  parent after O(branching factor) visits, which can otherwise cause it to sometimes not be
      //  explored further
      boolean isAntiDecisiveCompletePropagation = false;
      if ( lastNodeWasComplete && xiValues[(lNode.decidingRoleIndex+1)%tree.numRoles] == 0 )
      {
        isAntiDecisiveCompletePropagation = true;
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

      //  If we're propagating up through a complete node then the only possible valid score to
      //  propagate is that node's score
      if ( lNode.complete )
      {
        for (int lRoleIndex = 0; lRoleIndex < tree.numRoles; lRoleIndex++)
        {
          xiValues[lRoleIndex] = lNode.getAverageScore(lRoleIndex);
          xiSquaredValues[lRoleIndex] = lNode.getAverageSquaredScore(lRoleIndex);
        }
      }

      //  Choke off propagation that originated through an anti-decisive (losing) complete
      //  choice except for the first one through that parent
      if ( isAntiDecisiveCompletePropagation && lNode.numUpdates > 0 )
      {
        return System.nanoTime() - lStartTime;
      }

      if ( !lNode.complete )
      {
        double applicationWeight = (isAntiDecisiveCompletePropagation ? xiWeight/10 : xiWeight);

        for (int lRoleIndex = 0; lRoleIndex < tree.numRoles; lRoleIndex++)
        {
          assert(xiValues[lRoleIndex] < 100+EPSILON);
          if (lChildEdge != null)
          {
            TreeNode lChild = lNode.get(lChildEdge.getChildRef());
            //  Take the min of the apparent edge selection and the total num visits in the child
            //  This is necessary because when we re-expand a node that was previously trimmed we
            //  leave the edge with its old selection count even though the child node will be
            //  reset.
            int lNumChildVisits = Math.min(lChildEdge.getNumChildVisits(), lChild.numVisits);

            assert(lNumChildVisits > 0);
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

        //validateScoreVector(averageScores);
        lNode.numUpdates += applicationWeight;
      }

      //assert(lNode.numUpdates <= lNode.numVisits);
      lastNodeWasComplete = lNode.complete;

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
  TreeNode get(long xiNodeRef)
  {
    return get(tree.nodePool, xiNodeRef);
  }
}