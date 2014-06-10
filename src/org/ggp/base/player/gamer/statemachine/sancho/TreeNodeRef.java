package org.ggp.base.player.gamer.statemachine.sancho;


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
  private final TreeNode mNode;

  /**
   * Expected sequence number in the referenced node.
   */
  private final int mExpectedSequenceNumber;

  /**
   * Create a reference to a tree node.
   *
   * @param xiNode - the tree node.
   */
  public TreeNodeRef(TreeNode xiNode)
  {
    assert(!xiNode.freed) : "Attempt to create reference to freed node";
    mNode = xiNode;
    mExpectedSequenceNumber = xiNode.getSequenceNumber();
  }

  /**
   * @return the referenced tree node, or null if the reference is no longer valid (because the node has been
   *         recycled).
   */
  public TreeNode get()
  {
    if (mNode.getSequenceNumber() == mExpectedSequenceNumber)
    {
      assert(!mNode.freed) : "Invalid to successfully retrieve reference to freed node";
      return mNode;
    }

    return null;
  }

  /**
   * @return whether this reference refers to the same node as the specified reference.
   *
   * @param xiOther - the other reference.
   */
  public boolean hasSameReferand(TreeNodeRef xiOther)
  {
    return mExpectedSequenceNumber == xiOther.mExpectedSequenceNumber;
  }

  @Override
  public String toString()
  {
    return "Ref(" + Integer.toHexString(mNode.hashCode()) + ") = " + mExpectedSequenceNumber + ", node seq = " + mNode.getSequenceNumber();
  }
}