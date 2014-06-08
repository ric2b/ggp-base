package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.player.gamer.statemachine.sancho.pool.CappedPool;

/**
 * A reference to a tree node, containing a pointer to the tree node and the expected sequence number of the tree
 * node.  If, on access, the sequences numbers don't match, the underlying tree node has been recycled and is no
 * longer available to use.
 *
 * OCCUPANCY CRITICAL CLASS.
 */
public class TreeNodeRef
{
  /**
   * Referenced tree node.
   */
  private TreeNode node;

  /**
   * Expected sequence number.
   */
  private int seq;

  /**
   * Create a reference to a tree node.
   *
   * @param xiNode - the tree node.
   */
  public TreeNodeRef(TreeNode xiNode)
  {
    node = xiNode;
    seq = xiNode.seq;
  }

  /**
   * @return the referenced tree node, or null if the reference is no longer valid (because the node has been
   *         recycled).
   */
  public TreeNode get()
  {
    if (node.seq == seq)
    {
      return node;
    }

    return null;
  }

  /**
   * @return the referenced tree node provided that it is (a) still a valid reference and (b) not a reference to a
   *         null or freed node.
   */
  public TreeNode getLive()
  {
    if ((node.seq == seq) && (node.seq > 0))
    {
      return node;
    }

    return null;
  }

  /**
   * @return whether this is a reference to an unallocated node (i.e. one with the null sequence number).
   */
  public boolean isNullRef()
  {
    return seq == CappedPool.NULL_ITEM_SEQ;
  }

  /**
   * Mark this as referencing an unallocated node (i.e. one with the null sequence number).
   *
   * !! ARR Does this not indicate that the reference itself should be freed and nulled out it whatever variable it's
   * !! ARR currently being stored in by the caller?
   */
  public void clearRef()
  {
    seq = CappedPool.NULL_ITEM_SEQ;
  }

  /**
   * @return whether this reference refers to the same node as the specified reference.
   *
   * @param xiOther - the other reference.
   */
  public boolean hasSameReferand(TreeNodeRef xiOther)
  {
    return seq == xiOther.seq;
  }
}