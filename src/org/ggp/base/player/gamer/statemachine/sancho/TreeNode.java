package org.ggp.base.player.gamer.statemachine.sancho;

import java.io.File;
import java.io.PrintWriter;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.MCTSTree.MoveScoreInfo;
import org.ggp.base.player.gamer.statemachine.sancho.MachineSpecificConfiguration.CfgItem;
import org.ggp.base.player.gamer.statemachine.sancho.TreePath.TreePathElement;
import org.ggp.base.player.gamer.statemachine.sancho.pool.CappedPool;
import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool.ObjectAllocator;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
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

  private static final double        EPSILON = 1e-6;

  private static final boolean       USE_STATE_SIMILARITY_IN_EXPANSION = !MachineSpecificConfiguration.getCfgVal(
                                                           CfgItem.DISABLE_STATE_SIMILARITY_EXPANSION_WEIGHTING, false);

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
   * suitable available type.
   */
  /**
   * The tree in which we're a node.
   */
  MCTSTree tree;

  // A node reference.  This is a combination of the node's index in the allocating pool & a sequence number.  The
  // sequence number is incremented each time the node is re-used (thereby invalidating old references).
  //
  // The index is in the low 32 bits.  The sequence number is in the high 32 bits.
  private long                          mRef                = 0;

  public int                            numVisits           = 0;
  private int                           numUpdates          = 0;
  final ForwardDeadReckonInternalMachineState state;
  int                                   decidingRoleIndex;
  private boolean                       isTerminal          = false;
  boolean                               autoExpand          = false;
  boolean                               complete            = false;
  private boolean                       allChildrenComplete = false;
  Object[]                              children            = null;
  short                                 mNumChildren        = 0;
  short[]                               primaryChoiceMapping = null;
  private final ArrayList<TreeNode>     parents             = new ArrayList<>(1);
  private int                           sweepSeq;
  //private TreeNode sweepParent = null;
  boolean                               freed               = false;
  private double                        leastLikelyRunnerUpValue;
  private double                        mostLikelyRunnerUpValue;
  private short                         leastLikelyWinner   = -1;
  private short                         mostLikelyWinner    = -1;
  //  Note - the 'depth' of a node is an indicative measure of its distance from the
  //  initial state.  However, it is not an absolute count of the oath length.  This
  //  is because in some games the same state can occur at different depths (English Draughts
  //  exhibits this), which means that transitions to the same node can occur at multiple
  //  depths.  This approximate nature good enough for our current usage, but should be borne
  //  in mind if that usage is expanded.
  private short                         depth               = 0;
  private short                         completionDepth;

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
                if (edge.numChildVisits > mostSelectedRouteCount)
                {
                  mostSelectedRouteCount = edge.numChildVisits;
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

        for (int i = 0; i < tree.numRoles; i++)
        {
          tree.mCorrectedAverageScoresBuffer[i] /= totalWeight;
        }

        for (int i = 0; i < tree.numRoles; i++)
        {
          primaryPathParent.setAverageScore(i, tree.mCorrectedAverageScoresBuffer[i]);
        }

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

  private void markComplete(TreeNode fromDeciderNode, short atCompletionDepth)
  {
    if (!complete)
    {
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
            if (edge.mChildRef != NULL_REF)
            {
              TreeNode lChild = get(edge.mChildRef);
              if (lChild != null)
              {
                lChild.freeFromAncestor(this, null);
              }
            }
            deleteEdge(index);
          }
        }
      }

      freeChildren();
    }

    boolean decidingRoleWin = false;
    boolean mutualWin = true;

    for (int roleIndex = 0; roleIndex < tree.numRoles; roleIndex++)
    {
      if (getAverageScore(roleIndex) > 100 - EPSILON)
      {
        if (roleIndex == decidingRoleIndex &&
            (!tree.gameCharacteristics.isSimultaneousMove || roleIndex == 0 || hasSiblinglessParents()))
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

    for (TreeNode parent : parents)
    {
      if ( !parent.complete )
      {
        if (decidingRoleWin && !mutualWin)
        {
          // Win for whoever just moved after they got to choose so parent node is also decided
          parent.markComplete(this, completionDepth);

          //	Force win in this state means a very likely good move in sibling states
          //	so note that their selection probabilities should be increased
          //markCousinsAsPriorityToSelect();
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

  private void freeFromAncestor(TreeNode ancestor, TreeNode xiKeep)
  {
    //if (sweepParent == ancestor && sweepSeq == sweepInstance)
    //{
    //	LOGGER.info("Removing sweep parent");
    //}
    parents.remove(ancestor);

    if ((xiKeep != null) && (sweepSeq == tree.sweepInstance))
    {
      // We're re-rooting the tree and have already calculated that this node (which we happen to have reached through
      // a part of the tree that's being pruned) is reachable from the new root.  Therefore, we know it needs to be
      // kept.
      assert(parents.size() != 0 || this == xiKeep) : "Oops - no link left to new root";
      return;
    }

    if (parents.size() == 0)
    {
      for (int index = 0; index < mNumChildren; index++)
      {
        if (primaryChoiceMapping == null || primaryChoiceMapping[index] == index)
        {
          Object choice = children[index];

          TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
          TreeNode lChild;
          if (edge != null && edge.mChildRef != NULL_REF && (lChild = get(edge.mChildRef)) != null)
          {
            // Free the child (at least from us) and free our edge to it.
            if (lChild != xiKeep) // !! ARR Redundant check?  It will have been caught by the sweep check above.
            {
              lChild.freeFromAncestor(this, xiKeep);
            }
            deleteEdge(index);
          }
        }
      }

      freeNode();
    }
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

    for (int i = 0; i < tree.numRoles; i++)
    {
      tree.mNodeAverageScores[i] = 0;
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
          allImmediateChildrenComplete = false;
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

              if (lNode.getAverageScore(roleIndex) >= bestValue)
              {
                bestValue = lNode.getAverageScore(roleIndex);
                bestValueNode = lNode;

                if (bestValue > 100-EPSILON)
                {
                  //	Win for deciding role which they will choose unless it is also
                  //	a mutual win
                  boolean mutualWin = true;

                  for (int i = 0; i < tree.numRoles; i++)
                  {
                    if (lNode.getAverageScore(i) < 100-EPSILON)
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
        tree.gameCharacteristics.isSimultaneousMove && decidingRoleIndex == 0)
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
      if (tree.gameCharacteristics.isSimultaneousMove && !decidingRoleWin && decidingRoleIndex == 0)
      {
        //	This feels a bit of a hack, but it seems to work - in general when the outcome
        //	is complete for all choices but varies we err on the pessimistic side.
        //	However, if we just choose the worst result then a move with many bad results
        //	looks the same as one with a single bad result (with respect to opponent choices),
        //	so shade the score up slightly by the average (the 100:1 ratio is arbitrary)
        //	Note that just using the average also doesn't work, and will cause massive
        //	over-optimism.
        if ( decidingRoleIndex == tree.numRoles-1 )
        {
          for (int i = 0; i < tree.numRoles; i++)
          {
            tree.mBlendedCompletionScoreBuffer[i] = (worstDeciderNode.getAverageScore(i) * numUniqueChildren + tree.mNodeAverageScores[i]) / (numUniqueChildren+1);
          }
        }
        else
        {
          for (int i = 0; i < tree.numRoles; i++)
          {
            tree.mBlendedCompletionScoreBuffer[i] = tree.mNodeAverageScores[i];
          }
        }
        //	If a move provides a better-than-worst case in all uncles it provides a support
        //	floor the the worst that we can do with perfect play, so use that if its larger than
        //	what we would otherwise use
        if (floorDeciderNode != null &&
            floorDeciderNode.getAverageScore(roleIndex) > worstDeciderNode.getAverageScore(roleIndex))
        {
          for (int i = 0; i < tree.numRoles; i++)
          {
            tree.mBlendedCompletionScoreBuffer[i] = floorDeciderNode.getAverageScore(i);
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
    isTerminal = false;
    autoExpand = false;
    leastLikelyWinner = -1;
    mostLikelyWinner = -1;
    complete = false;
    allChildrenComplete = false;
    freed = (xiTree == null);

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

  private int getInstanceId()
  {
    // The instance ID is packed into the bottom 32 bits of the node reference.
    return (int)mRef;
  }

  public double getAverageScore(int roleIndex)
  {
    return tree.scoreVectorPool.getAverageScore(getInstanceId(), roleIndex);
  }

  public void setAverageScore(int roleIndex, double value)
  {
    assert(-EPSILON<=value);
    assert(100+EPSILON>=value);
    tree.scoreVectorPool.setAverageScore(getInstanceId(), roleIndex, value);
  }

  public double getAverageSquaredScore(int roleIndex)
  {
    return tree.scoreVectorPool.getAverageSquaredScore(getInstanceId(), roleIndex);
  }

  public void setAverageSquaredScore(int roleIndex, double value)
  {
    tree.scoreVectorPool.setAverageSquaredScore(getInstanceId(), roleIndex, value);
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
              numInwardVisits += edge.numChildVisits;
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
  private void markTreeForSweep()
  {
    if (sweepSeq != tree.sweepInstance)
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
            get(edge.mChildRef).markTreeForSweep();
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

      if (decidingRoleIndex == tree.numRoles - 1)
      {
        tree.nodeFreed(this);
      }

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
    //LOGGER.debug("Free all but rooted in state: " + descendant.state);

    // Mark the live portions of the tree.  This allows us to tidy up the state without repeatedly visiting live parts
    // of the tree.
    tree.sweepInstance++;
    descendant.markTreeForSweep();
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

          if (lNode != descendant)
          {
            // Free the child (at least from us)
            lNode.freeFromAncestor(this, descendant);
          }

          // Delete our edge to the child anyway.  (We only set "descendant" when re-rooting the tree.  In that case,
          // we don't need the edge any more.)
          deleteEdge(index);
        }
      }
    }

    freeNode();
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
      TreeNode leastLikely = selectLeastLikelyExpandedNode(null, 0);

      if (leastLikely != null)
      {
        //  Free the children of the chosen node from its parentage
        //  and de-expand it
        leastLikely.unexpand();
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
          if ( ((TreeEdge)choice).mChildRef != NULL_REF )
          {
            TreeNode child = get(((TreeEdge)choice).mChildRef);

            if ( child != null )
            {
              child.freeFromAncestor(this, null);
            }
          }

          deleteEdge(index);
        }
      }
    }

    freeChildren();
  }

  private static int leastLikelyDisposalCount = 0;
  public TreeNode selectLeastLikelyExpandedNode(TreeEdge from, int depth)
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
                  if (edge.numChildVisits == 0 )
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
                    if (edge.numChildVisits == 0)
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

      return get(selectedEdge.mChildRef).selectLeastLikelyExpandedNode(selectedEdge, depth + 1);
    }

    //  Children of the root should never be trimmed.  For us to have wanted to unexpand
    //  the root all its children must be unexpanded.  This is possible in factored games
    //  where one factor has a compleet root, so all node allocation occurs in the other
    //  factor(s)'s tree(s), so it is only a warned condition at this level
    if (depth < 1)
    {
      if ( tree.factor == null )
      {
        LOGGER.warn("Attempt to select unlikely node at depth " + depth);
      }

      return null;
    }

    return this;
  }

  private StateInfo calculateTerminalityAndAutoExpansion(ForwardDeadReckonInternalMachineState theState)
  {
    StateInfo result = StateInfo.bufferInstance;

    result.isTerminal = false;
    result.autoExpand = false;

    // Check if the goal value is latched.
    if (tree.numRoles == 1) // !! ARR 1P Latches
    {
      Integer lLatchedScore = tree.underlyingStateMachine.getLatchedScore(state);
      if (lLatchedScore != null)
      {
        result.isTerminal = true;
        result.terminalScore[0] = lLatchedScore;
      }
    }

    if (tree.underlyingStateMachine.isTerminal(theState))
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

      for(int i = 0; i < tree.numRoles && nonNoopCount < 2; i++ )
      {
        for(ForwardDeadReckonLegalMoveInfo info : tree.underlyingStateMachine.getLegalMoves(theState, tree.roleOrdering.roleIndexToRole(i), tree.factor))
        {
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

  private void createChildNodeForEdge(TreeEdge edge, ForwardDeadReckonLegalMoveInfo[] jointPartialMove)
  {
    boolean isPseudoNullMove = (tree.factor != null);
    int roleIndex = (decidingRoleIndex + 1) % tree.numRoles;

    for (int i = 0; i <= roleIndex; i++)
    {
      if (jointPartialMove[i].inputProposition != null)
      {
        isPseudoNullMove = false;
      }
    }

    assert(state != null);
    assert(edge.mChildRef == NULL_REF);

    ForwardDeadReckonInternalMachineState newState = null;
    if (roleIndex == tree.numRoles - 1)
    {
      newState = tree.mNextStateBuffer;
      tree.underlyingStateMachine.getNextState(state, tree.factor, jointPartialMove, newState);
    }
    TreeNode newChild = tree.allocateNode(newState, this, isPseudoNullMove);

    assert(!newChild.freed);
    edge.setChild(newChild);

    newChild.decidingRoleIndex = roleIndex;
    newChild.depth = (short)(depth + 1);

    if (roleIndex != tree.numRoles - 1)
    {
      // assert(newState == null);
      newChild.autoExpand = autoExpand;
      newChild.setState(state);
    }

    //  If we transition into a complete node we need to have it re-process that
    //  completion again in the light of the new parentage
    if (newChild.complete)
    {
      tree.completedNodeQueue.add(newChild);
    }
  }

  public void expand(TreePathElement pathTo, ForwardDeadReckonLegalMoveInfo[] jointPartialMove)
  {
    ProfileSection methodSection = ProfileSection.newInstance("TreeNode.expand");
    try
    {
      //  Find the role this node is choosing for
      int roleIndex = (decidingRoleIndex + 1) % tree.numRoles;

      //  Don't bother evaluating terminality of children above the earliest completion depth
      boolean evaluateTerminalOnNodeCreation = (tree.evaluateTerminalOnNodeCreation && depth >= tree.gameCharacteristics.getEarliestCompletionDepth());

      if (roleIndex == 0)
      {
        boolean parentEvaluatedTerminalOnNodeCreation = (tree.evaluateTerminalOnNodeCreation && depth > tree.gameCharacteristics.getEarliestCompletionDepth());
        if (!parentEvaluatedTerminalOnNodeCreation && mNumChildren == 0)
        {
          StateInfo info = calculateTerminalityAndAutoExpansion(state);

          isTerminal = info.isTerminal;
          autoExpand = info.autoExpand;

          if (isTerminal)
          {
            markComplete(info.terminalScore, depth);
            return;
          }
        }
      }

      assert (mNumChildren == 0);
      {
        Role choosingRole = tree.roleOrdering.roleIndexToRole(roleIndex);
        int topMoveWeight = 0;

        if ( pathTo != null && pathTo.getEdge().numChildVisits > 1 )
        {
          //  If the node is unexpanded, yet has already been visited, this must
          //  be a re-expansion following trimming.
          //  Note - the first can visit occur without expansion as a random child
          //  of the last expanded node will be chosen to rollout from
          tree.numReExpansions++;
        }
        //validateAll();

        //LOGGER.debug("Expand our moves from state: " + state);
        Collection<ForwardDeadReckonLegalMoveInfo> moves = tree.underlyingStateMachine.getLegalMoves(state,
                                                                                                     choosingRole,
                                                                                                     tree.factor);
        assert(moves.size() > 0);
        assert(moves.size() <= MCTSTree.MAX_SUPPORTED_BRANCHING_FACTOR);

        // If the child array isn't large enough, expand it.
        mNumChildren = (short)moves.size();
        if (mNumChildren > children.length)
        {
          children = new Object[tree.gameCharacteristics.getChoicesHighWaterMark(mNumChildren)];
        }

        if (USE_STATE_SIMILARITY_IN_EXPANSION)
        {
          if (mNumChildren > 1)
          {
            topMoveWeight = tree.mStateSimilarityMap.getTopMoves(state, jointPartialMove, tree.mNodeTopMoveCandidates);
          }
        }

        short lMoveIndex = 0;
        for (ForwardDeadReckonLegalMoveInfo newChoice : moves)
        {
          boolean isPseudoNullMove = (tree.factor != null);

          jointPartialMove[roleIndex] = newChoice;
          for (int i = 0; i <= roleIndex; i++)
          {
            if (jointPartialMove[i].inputProposition != null)
            {
              isPseudoNullMove = false;
            }
          }

          ForwardDeadReckonInternalMachineState newState = tree.mChildStatesBuffer[lMoveIndex];
          if (roleIndex == tree.numRoles - 1)
          {
            newState = tree.mChildStatesBuffer[lMoveIndex];
            tree.underlyingStateMachine.getNextState(state,
                                                     tree.factor,
                                                     jointPartialMove,
                                                     newState);
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
          if (!isPseudoNullMove)
          {
            for (short i = 0; i < lMoveIndex; i++)
            {
              if (children[i] != null &&
                  roleIndex == tree.numRoles - 1 &&
                  tree.mChildStatesBuffer[i].equals(tree.mChildStatesBuffer[lMoveIndex]))
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

          children[lMoveIndex] = newChoice;
          lMoveIndex++;
        }

        if (evaluateTerminalOnNodeCreation && roleIndex == tree.numRoles - 1)
        {
          for (lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
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
                  newChild.markComplete(info.terminalScore, (short)(depth + 1));
                }
              }
            }
          }
        }

        if (USE_STATE_SIMILARITY_IN_EXPANSION && topMoveWeight > 0)
        {
          for (lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
          {
            if ((primaryChoiceMapping == null || primaryChoiceMapping[lMoveIndex] == lMoveIndex) )
            {
              Object choice = children[lMoveIndex];
              TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
              if (edge == null || !get(edge.mChildRef).isTerminal)
              {
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

        if (roleIndex == tree.numRoles - 1)
        {
          for (lMoveIndex = 0; lMoveIndex < mNumChildren; lMoveIndex++)
          {
            if ((primaryChoiceMapping == null || primaryChoiceMapping[lMoveIndex] == lMoveIndex) )
            {
              // Determine the heuristic value for this child.
              for (int lii = 0; lii < tree.numRoles; lii++)
              {
                tree.mNodeHeuristicValues[lii] = 0;
              }
              tree.heuristic.getHeuristicValue(tree.mChildStatesBuffer[lMoveIndex],
                                               state,
                                               tree.mNodeHeuristicValues,
                                               tree.mNodeHeuristicWeight);
              if (tree.mNodeHeuristicWeight.value > 0)
              {
                double heuristicSquaredDeviation = 0;

                //validateScoreVector(heuristicScores);

                // Set the heuristic values, although note that this doesn't actually apply them.  There need to be some
                // recorded samples before the averageScores have any meaning.
                for (int i = 0; i < tree.numRoles; i++)
                {
                  //newChild.averageScores[i] = heuristicScores[i];
                  double lDeviation = tree.root.getAverageScore(i) - tree.mNodeHeuristicValues[i];
                  heuristicSquaredDeviation += (lDeviation * lDeviation);
                }

                // Only apply the heuristic values if the current root has sufficient visits and there is some deviation
                // between the root's scores and the heuristic scores in the new child.
                if (heuristicSquaredDeviation > 0.01 && tree.root.numVisits > 50)
                {
                  //  Create the edge if necessary
                  TreeEdge edge;

                  if ( children[lMoveIndex] instanceof TreeEdge )
                  {
                    edge = (TreeEdge)children[lMoveIndex];
                  }
                  else
                  {
                    edge = tree.edgePool.allocate(tree.mTreeEdgeAllocator);
                    edge.setParent(this, (ForwardDeadReckonLegalMoveInfo)children[lMoveIndex]);
                    children[lMoveIndex] = edge;
                    jointPartialMove[roleIndex] = edge.mPartialMove;
                  }

                  if ( edge.mChildRef == NULL_REF )
                  {
                    createChildNodeForEdge(edge, jointPartialMove);

                    assert(!evaluateTerminalOnNodeCreation || !calculateTerminalityAndAutoExpansion(get(edge.mChildRef).state).isTerminal);
                  }

                  TreeNode newChild = get(edge.mChildRef);

                  //  If this turns out to be a transition into an already visited child
                  //  then do not apply the heuristics
                  if (newChild.numVisits == 0 && !newChild.isTerminal)
                  {
                    for (int i = 0; i < tree.numRoles; i++)
                    {
                      newChild.setAverageScore(i, (newChild.getAverageScore(i)*newChild.numUpdates + tree.mNodeHeuristicValues[i]*tree.mNodeHeuristicWeight.value)/(newChild.numUpdates+tree.mNodeHeuristicWeight.value));
                    }
                    // Use the heuristic confidence to guide how many virtual rollouts to pretend there have been through
                    // the new child.
                    newChild.numUpdates = tree.mNodeHeuristicWeight.value;
                    assert(!Double.isNaN(newChild.getAverageScore(0)));

                    newChild.numVisits = newChild.numUpdates;
                  }
                }
              }
            }
          }
        }

        //validateAll();

        if (evaluateTerminalOnNodeCreation && roleIndex == tree.numRoles - 1 )
        {
          boolean completeChildFound = false;

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
                  lNode.markComplete(lNode, lNode.depth);
                  completeChildFound = true;
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
            checkChildCompletion(true);
          }
        }
//
//        if (USE_STATE_SIMILARITY_IN_EXPANSION && !complete && roleIndex == 0)
//        {
//          tree.mStateSimilarityMap.add(getRef());
//        }
        //validateAll();
      }
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
            total += get(edge.mChildRef).getAverageScore(0) * edge.numChildVisits;
            visitTotal += edge.numChildVisits;
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

  private double explorationUCT(int effectiveTotalVists,
                                TreeEdge edge,
                                int roleIndex)
  {
    // Extract the common parts of the calculation to avoid making expensive calls twice.
    double lCommon = 2 * Math.log(Math.max(effectiveTotalVists, edge.numChildVisits) + 1) / edge.numChildVisits;

    // When we propagate adjustments due to completion we do not also adjust the variance contribution so this can
    // result in 'impossibly' low (aka negative) variance - take a lower bound of 0
    double roleAverageScore = getAverageScore(roleIndex);
    double roleAverageSquaredScore = getAverageSquaredScore(roleIndex);

    double varianceBound = Math.max(0, roleAverageSquaredScore -
                                    roleAverageScore *
                                    roleAverageScore) /
                                    10000 +
                                    Math.sqrt(lCommon);
    double result = tree.gameCharacteristics.getExplorationBias() *
           Math.sqrt(Math.min(0.5, varianceBound) * lCommon) / tree.roleRationality[roleIndex];

    result *= (1 + edge.explorationAmplifier);
    return result;
  }

  private double getAverageCousinMoveValue(TreeEdge relativeTo, int roleIndex)
  {
    TreeNode lNode = get(relativeTo.mChildRef);
    if (lNode.decidingRoleIndex == 0)
    {
      return lNode.getAverageScore(roleIndex) / 100;
    }
    else if (tree.cousinMovesCachedFor == NULL_REF || get(tree.cousinMovesCachedFor) != this)
    {
      tree.cousinMovesCachedFor = getRef();
      tree.cousinMoveCache.clear();

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
                if ( child.primaryChoiceMapping == null || child.primaryChoiceMapping[nephewIndex] == nephewIndex )
                {
                  Object nephewChoice = child.children[nephewIndex];

                  TreeEdge nephewEdge = (nephewChoice instanceof TreeEdge ? (TreeEdge)nephewChoice : null);
                  if (nephewEdge == null || nephewEdge.mChildRef == NULL_REF)
                  {
                    continue;
                  }

                  TreeNode nephew = get(nephewEdge.mChildRef);
                  if (nephew != null)
                  {
                    Move move = nephewEdge.mPartialMove.move;
                    MoveScoreInfo accumulatedMoveInfo = tree.cousinMoveCache.get(move);
                    if (accumulatedMoveInfo == null)
                    {
                      accumulatedMoveInfo = tree.new MoveScoreInfo();
                      tree.cousinMoveCache.put(move, accumulatedMoveInfo);
                    }

                    accumulatedMoveInfo.averageScore = (accumulatedMoveInfo.averageScore *
                        accumulatedMoveInfo.numSamples + nephew.getAverageScore(roleIndex)) /
                        (accumulatedMoveInfo.numSamples + 1);
                    accumulatedMoveInfo.numSamples++;
                  }
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
      LOGGER.warn("No newphews found for search move including own child!");
      tree.cousinMovesCachedFor = NULL_REF;
      //getAverageCousinMoveValue(relativeTo);
      return lNode.getAverageScore(roleIndex) / 100;
    }
    return accumulatedMoveInfo.averageScore / 100;
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
          if (edge2 != null && edge2.mChildRef != NULL_REF && get(edge2.mChildRef).getAverageScore(roleIndex) > bestChildScore)
          {
            bestChildScore = get(edge2.mChildRef).getAverageScore(roleIndex);
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
      return getAverageCousinMoveValue(inboundEdge, roleIndex);
    }

    double result = lInboundChild.getAverageScore(roleIndex) / 100;// + heuristicValue()/Math.log(numVisits+2);// + averageSquaredScore/20000;
    return result;
  }

  TreePathElement select(TreePath path, ForwardDeadReckonLegalMoveInfo[] jointPartialMove)
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
         selectedIndex = 0;
      }
      else
      {
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

                if (edge.numChildVisits == 0 && !c.complete)
              {
                // small random number to break ties randomly in unexpanded nodes
                uctValue = 1000 + tree.r.nextDouble() * EPSILON + edge.explorationAmplifier;
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
                  if (edge.numChildVisits == 0)
                  {
                    // small random number to break ties randomly in unexpanded nodes
                    uctValue = 1000 + tree.r.nextDouble() * EPSILON + edge.explorationAmplifier;
                  }
                  else
                  {
                    assert(edge.numChildVisits <= c.numVisits);

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
              else
              {
                //  A null child ref in an extant edge is a not-yet selected through
                //  path which is asserted to be non-terminal and unvisited

                // small random number to break ties randomly in unexpanded nodes
                uctValue = 1000 + tree.r.nextDouble() * EPSILON + (edge == null ? 0 : edge.explorationAmplifier);

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
      createChildNodeForEdge(selected, jointPartialMove);

      assert(!tree.evaluateTerminalOnNodeCreation ||
             depth < tree.gameCharacteristics.getEarliestCompletionDepth() ||
             !calculateTerminalityAndAutoExpansion(get(selected.mChildRef).state).isTerminal);
    }

    assert(get(selected.mChildRef) != null);

    if (USE_STATE_SIMILARITY_IN_EXPANSION && !complete && roleIndex == 0)
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
      bestSelectedEdge.numChildVisits++;
      mostLikelyWinner = -1;
    }

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
                        arrivalPath.mPartialMove.move +
                        " scores " + stringizeScoreVector() + "(ref " + mRef +
                        ") - visits: " + numVisits + " (" +
                        arrivalPath.numChildVisits + ")");
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

  private void postProcessResponseCompletion()
  {
    if (mNumChildren != 0)
    {
      if (mNumChildren > 1)
      {
        if (decidingRoleIndex != (tree.numRoles - 1) % tree.numRoles)
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
                if (lNode2.getAverageScore(0) <= tree.mGameSearcher.lowestRolloutScoreSeen && lNode2.complete)
                {
                  LOGGER.info("Post-processing completion of response node");
                  markComplete(lNode2, lNode2.completionDepth);
                }
              }
            }
          }
        }
      }
      else if ( children[0] instanceof TreeEdge )
      {
        TreeEdge edge2 = (TreeEdge)children[0];

        if (edge2.mChildRef != NULL_REF && get(edge2.mChildRef) != null)
        {
          get(edge2.mChildRef).postProcessResponseCompletion();
        }
      }
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

    if (!lRecursiveCall)
    {
      for (int lii = 0; lii < mNumChildren; lii++)
      {
        Object choice = children[lii];
        TreeEdge edge = (choice instanceof TreeEdge ? (TreeEdge)choice : null);
        if (edge != null && edge.mChildRef != NULL_REF)
        {
          TreeNode lNode = get(edge.mChildRef);
          if (lNode.complete)
          {
            anyComplete = true;
          }
          else if (lNode.mNumChildren != 0 &&
              tree.mGameSearcher.lowestRolloutScoreSeen < 100 && tree.gameCharacteristics.numRoles <= 2 &&
              !tree.gameCharacteristics.isSimultaneousMove)
          {
            //	Post-process completions of children with respect the the observed rollout score range
            lNode.postProcessResponseCompletion();
          }
        }
      }
    }

    tree.processNodeCompletions();

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
          moveScore = (child.completionDepth - tree.mGameSearcher.getRootDepth()) - 100;
          assert(moveScore <= 0);
          assert(moveScore >= -100);
        }

        selectionScore = moveScore;
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
          moveScore <= tree.mGameSearcher.lowestRolloutScoreSeen &&
          tree.mGameSearcher.lowestRolloutScoreSeen < 100)
      {
        continue;
      }
      if (bestNode == null ||
          selectionScore > bestScore ||
          (selectionScore == bestScore && child.complete && (child.completionDepth < bestNode.completionDepth || !bestNode.complete)) ||
          (bestNode.complete && !child.complete &&
          bestNode.getAverageScore(roleIndex) <= tree.mGameSearcher.lowestRolloutScoreSeen && tree.mGameSearcher.lowestRolloutScoreSeen < 100))
      {
        bestNode = child;
        bestScore = selectionScore;
        bestMoveScore = bestScore;
        bestEdge = edge;
      }
      if (child.getAverageScore(roleIndex) > bestRawScore ||
          (child.getAverageScore(roleIndex) == bestRawScore && child.complete && child.getAverageScore(roleIndex) > 0))
      {
        bestRawScore = child.getAverageScore(roleIndex);
        rawBestEdgeResult = edge;
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

  public void rollOut(TreePath path, Pipeline xiPipeline, boolean forceSynchronous) throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
  {
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

      updateStats(tree.mNodeAverageScores,
                  tree.mNodeAverageSquaredScores,
                  path,
                  true);
      tree.mPathPool.free(path);

      return;
    }

    assert(decidingRoleIndex == tree.numRoles - 1) : "Attempt to rollout from an incomplete-information node";

    assert(!freed) : "Rollout node is a freed node";
    assert(path.isValid()) : "Rollout path isn't valid";

    // Get a rollout request object.
    RolloutRequest lRequest;
    if (ThreadControl.ROLLOUT_THREADS > 0 && !forceSynchronous)
    {
      // Get a request slot from the pipeline.
      if (!xiPipeline.canExpand())
      {
        // The pipeline is full.  We can't expand it until we've done some back-propagation.  Even though none was
        // available at the start of the expansion, we'll just have to wait.
        tree.mGameSearcher.processCompletedRollouts(true);

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

    lRequest.mState.copy(state);
    lRequest.mNodeRef = getRef();
    lRequest.mSampleSize = tree.gameCharacteristics.getRolloutSampleSize();
    lRequest.mPath = path;
    lRequest.mFactor = tree.factor;
    lRequest.mPlayedMovesForWin = ((tree.gameCharacteristics.numRoles == 1 && tree.factor == null) ? new LinkedList<ForwardDeadReckonLegalMoveInfo>() : null);

    //request.moveWeights = masterMoveWeights.copy();
    tree.numNonTerminalRollouts += lRequest.mSampleSize;

    if (lRequest != tree.mNodeSynchronousRequest)
    {
      // Queue the request for processing.
      lRequest.mEnqueueTime = System.nanoTime();
      xiPipeline.completedExpansion();

      // Whilst waiting for the request to be rolled out, update the visit count of this node to encourage the search to
      // go down a different path when selecting the next node for rollout.
      updateVisitCounts(lRequest.mPath);
    }
    else
    {
      // Do the rollout and back-propagation synchronously (on this thread).
      assert(ThreadControl.ROLLOUT_THREADS == 0 || forceSynchronous);
      lRequest.process(tree.underlyingStateMachine, tree.mOurRole, tree.roleOrdering);
      assert(!Double.isNaN(lRequest.mAverageScores[0]));
      updateStats(lRequest.mAverageScores,
                  lRequest.mAverageSquaredScores,
                  lRequest.mPath,
                  true);
      tree.mPathPool.free(lRequest.mPath);
      lRequest.mPath = null;
    }
  }

  public void updateVisitCounts(TreePath path)
  {
    TreePathElement element = path.getCurrentElement();
    TreeEdge childEdge = (element == null ? null : element.getEdge());

    numVisits++;// += sampleSize;
    assert(numVisits > numUpdates);

    //for(TreeNode parent : parents)
    //{
    //	if (!parent.complete || isSimultaneousMove || isMultiPlayer)
    //	{
    //		parent.updateVisitCounts(sampleSize, path);
    //	}
    //}

    if (childEdge != null)
    {
      assert(get(childEdge.mChildRef) != null);
      childEdge.numChildVisits++;
      assert (childEdge.numChildVisits <= get(childEdge.mChildRef).numVisits);
    }

    if (path.hasMore())
    {
      TreeNode node = path.getNextNode();
      if (node != null)
      {
        node.updateVisitCounts(path);
      }
    }
  }

  /**
   * Update the tree with details from a rollout, starting with this node, then working up the path.
   *
   * @param xiValues                  - The per-role rollout values.
   * @param xiSquaredValues           - The per-role squared values (for computing variance).
   * @param xiPath                    - The path taken through the tree for the rollout.
   * @param xiIsCompletePseudoRollout - Whether this is a complete pseudo-rollout.
   */
  public void updateStats(double[] xiValues,
                          double[] xiSquaredValues,
                          TreePath xiPath,
                          boolean xiIsCompletePseudoRollout)
  {
    TreeNode lNextNode;
    for (TreeNode lNode = this; lNode != null; lNode = lNextNode)
    {
      TreePathElement lElement = xiPath.getCurrentElement();
      TreeEdge lChildEdge = (lElement == null ? null : lElement.getEdge());
      lNextNode = null;

      // Across a turn end it is possible for queued paths to run into freed nodes due to trimming of the tree at the
      // root to advance the turn.  Rather than add locking and force clearing the rollout pipeline synchronously on
      // turn start it is more efficient to simply abort the update when the path leads to a no-longer extant region of
      // the tree.
      if (xiPath.hasMore())
      {
        lNextNode = xiPath.getNextNode();
        if (lNextNode == null)
        {
          return;
        }
      }

      // Do the stats update for the selected node.
      assert(lNode.numUpdates <= lNode.numVisits);

      double[] lOverrides = (lElement == null ? null : lElement.getScoreOverrides());
      if (lOverrides != null)
      {
        xiValues = lOverrides;
      }

      for (int lRoleIndex = 0; lRoleIndex < tree.numRoles; lRoleIndex++)
      {
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
          int lNumChildVisits = Math.min(lChildEdge.numChildVisits, lChild.numVisits);

          assert(lNumChildVisits > 0 || xiIsCompletePseudoRollout);
          //  Propagate a value that is a blend of this rollout value and the current score for the child node
          //  being propagated from, according to how much of that child's value was accrued through this path
          if (xiValues != lOverrides && lNumChildVisits > 0)
          {
            xiValues[lRoleIndex] = (xiValues[lRoleIndex] * lNumChildVisits + lChild.getAverageScore(lRoleIndex) *
                                    (lChild.numVisits - lNumChildVisits)) /
                                   lChild.numVisits;
          }
        }

        if (!lNode.complete)
        {
          lNode.setAverageScore(lRoleIndex,
                                (lNode.getAverageScore(lRoleIndex) * lNode.numUpdates + xiValues[lRoleIndex]) /
                                (lNode.numUpdates + 1));
          lNode.setAverageSquaredScore(lRoleIndex,
                                       (lNode.getAverageSquaredScore(lRoleIndex) *
                                        lNode.numUpdates + xiSquaredValues[lRoleIndex]) /
                                       (lNode.numUpdates + 1));
        }

        lNode.leastLikelyWinner = -1;
        lNode.mostLikelyWinner = -1;
      }

      if (xiIsCompletePseudoRollout)
      {
        lNode.numVisits++;

        if ((!lNode.complete ||
             tree.gameCharacteristics.isSimultaneousMove ||
             tree.gameCharacteristics.numRoles > 2) &&
            lChildEdge != null)
        {
          lChildEdge.numChildVisits++;
          assert(lChildEdge.numChildVisits <= lNode.get(lChildEdge.mChildRef).numVisits);
        }
      }

      //validateScoreVector(averageScores);
      lNode.numUpdates++;
      assert(lNode.numUpdates <= lNode.numVisits);
    }
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