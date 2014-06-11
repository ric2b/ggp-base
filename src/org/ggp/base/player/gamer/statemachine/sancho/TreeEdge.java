package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool.ObjectAllocator;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;

/**
 * An edge in an MCTS Tree.
 */
public class TreeEdge
{
  /**
   * Utility class for allocating tree edges from a CappedPool.
   */
  public static class TreeEdgeAllocator implements ObjectAllocator<TreeEdge>
  {
    /**
     * Create an allocator for nodes in the the specified MCTS tree.
     *
     * @param xiTree - the tree.
     */
    public TreeEdgeAllocator()
    {
    }

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
   * The move represented by this edge.  This is final for the lifetime of the edge (until recycled).
   */
  ForwardDeadReckonLegalMoveInfo partialMove;

  /**
   * The child node reached by performing the (partial) move in the parent state.  (The parent doesn't need to be
   * explicitly referenced in an edge.  It is implicit in the node from which this edge is referenced.)
   */
  long   mChildRef                  = TreeNode.NULL_REF;

  int    numChildVisits             = 0;
  double explorationAmplifier       = 0;

  /**
   * Create a tree edge.
   *
   * Immediately after creation, the caller is expected to fill in partialMove.  (It isn't passed as a parameter here
   * purely to make pooled creation simpler.)
   */
  TreeEdge()
  {
  }

  /**
   * @return a human-readable string describing this edge.
   */
  public String descriptiveName()
  {
    if ( partialMove.isPseudoNoOp )
    {
      return "<Pseudo no-op>";
    }

    return partialMove.move.toString();
  }

  /**
   * Reset an edge in preparation for re-use.
   */
  public void reset()
  {
    numChildVisits = 0;
    mChildRef = TreeNode.NULL_REF;
    partialMove = null;
    explorationAmplifier = 0;
  }
}