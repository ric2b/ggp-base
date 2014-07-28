package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool.ObjectAllocator;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;

/**
 * An edge in an MCTS Tree.
 */
public class TreeEdge
{
  /**
   * Utility class for allocating tree edges from a pool.
   */
  public static class TreeEdgeAllocator implements ObjectAllocator<TreeEdge>
  {
    @Override
    public TreeEdge newObject(int xiPoolIndex)
    {
      return new TreeEdge();
    }

    @Override
    public void resetObject(TreeEdge xiEdge, boolean xiFree)
    {
      xiEdge.reset();
    }

    @Override
    public boolean shouldReset(TreeEdge xiEdge)
    {
      assert(false) : "Shouldn't call shouldReset(TreeEdge)";
      return false;
    }
  }

  /**
   * The parent node from which this edge leads.
   *
   * This is final for the logical lifetime of the edge (i.e. until it is recycled).  It is never TreeNode.NULL_REF.
   */
  long                           mParentRef                 = TreeNode.NULL_REF;

  /**
   * The move, performed in the parent, which this edge represents.
   *
   * This is final for the logical lifetime of the edge (i.e. until it is recycled).
   */
  ForwardDeadReckonLegalMoveInfo mPartialMove;

  /**
   * The child reached by performing the move in the parent.
   *
   * This may be TreeNode.NULL_REF if the child node is yet to be created.
   */
  long                           mChildRef                  = TreeNode.NULL_REF;

  /**
   * Top bit of the numChildVisits is used to encode an edge re-expansion event
   */
  static final private int hasBeenTrimmedMask = 0x80000000;
  static final private int childVisitNumVisitsMask = ~hasBeenTrimmedMask;
  private int  numChildVisits       = 0;
  double explorationAmplifier       = 0;

  /**
   * Create a tree edge.
   *
   * Immediately after creation, the caller is expected to call setParent.  (The parameters aren't passed here purely to
   * make pooled creation simpler.)
   */
  TreeEdge()
  {
  }

  /**
   * @return number of times this edge has been selected through
   */
  public int getNumChildVisits()
  {
    return (numChildVisits & childVisitNumVisitsMask);
  }

  /**
   * @return whether this edge has been re-expanded after trimming
   */
  public boolean getHasBeenTrimmed()
  {
    return (numChildVisits & hasBeenTrimmedMask) != 0;
  }

  /**
   * Increment the number of times this edge has been selected through
   */
  public void incrementNumVisits()
  {
    numChildVisits++;
  }

  /**
   * Note that this edge has been trimmed
   */
  public void setHasBeenTrimmed()
  {
    numChildVisits |= hasBeenTrimmedMask;
  }

  /**
   * Set the edge's parent (and the move from the parent).
   *
   * @param xiParent      - the parent.
   * @param xiPartialMove - the move.
   */
  public void setParent(TreeNode xiParent, ForwardDeadReckonLegalMoveInfo xiPartialMove)
  {
    mParentRef = xiParent.getRef();
    mPartialMove = xiPartialMove;

    assert(mParentRef != TreeNode.NULL_REF);
    assert(mChildRef == TreeNode.NULL_REF ||
           TreeNode.get(xiParent.tree.nodePool, mChildRef).tree == xiParent.tree);
    assert(!xiPartialMove.isPseudoNoOp || xiParent == xiParent.tree.root || xiParent.mNumChildren == 1);
  }

  /**
   * Set the edge's child.
   *
   * @param xiChild - the child.
   */
  public void setChild(TreeNode xiChild)
  {
    mChildRef = xiChild.getRef();

    assert(mChildRef != TreeNode.NULL_REF);
    assert(mParentRef == TreeNode.NULL_REF ||
           TreeNode.get(xiChild.tree.nodePool, mParentRef).tree == xiChild.tree);
  }

  /**
   * @return a human-readable string describing this edge.
   */
  public String descriptiveName()
  {
    if ( mPartialMove.isPseudoNoOp )
    {
      return "<Pseudo no-op>";
    }

    return mPartialMove.move.toString();
  }

  /**
   * Reset an edge in preparation for re-use.
   */
  public void reset()
  {
    mParentRef = TreeNode.NULL_REF;
    mChildRef = TreeNode.NULL_REF;
    numChildVisits = 0;
    mPartialMove = null;
    explorationAmplifier = 0;
  }
}