package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.player.gamer.statemachine.sancho.pool.Pool.ObjectAllocator;

/**
 * Path through an MCTS tree.
 */
public class TreePath
{
  /**
   * The maximum possible path length.  Game of "Connect 4 Larger" could run to 400.  We'll give a bit of room for
   * bigger games.
   */
  public static final int MAX_PATH_LEN = 512;

  /**
   * Utility class for allocating tree paths from a pool.
   */
  public static class TreePathAllocator implements ObjectAllocator<TreePath>
  {
    private final MCTSTree mAllocatorTree;

    /**
     * Create an allocator for nodes in the the specified MCTS tree.
     *
     * @param xiTree - the tree.
     */
    public TreePathAllocator(MCTSTree xiTree)
    {
      mAllocatorTree = xiTree;
    }

    @Override
    public TreePath newObject(int xiPoolIndex)
    {
      return new TreePath(mAllocatorTree);
    }

    @Override
    public void resetObject(TreePath xiPath, boolean xiFree)
    {
      xiPath.reset(mAllocatorTree);
    }

    @Override
    public boolean shouldReset(TreePath xiPath)
    {
      assert(false) : "Shouldn't call shouldReset(TreePath)";
      return false;
    }
  }

  /**
   * Individual element in a tree path
   */
  class TreePathElement
  {
    // The parent node, the edge leading from it and the child.  It is only valid to access the edge if both node
    // references are still valid.
    private long     mParentRef;
    private TreeEdge mEdge;
    long     mChildRef;

    // Score overrides to use above this point in the path.
    private double[] scoreOverrides;

    /**
     * Create a TreePathElement.  For use in pre-allocation.
     */
    public TreePathElement()
    {
      scoreOverrides = null; // !! ARR Pre-allocate this too.
      reset();
    }

    /**
     * Set up the TreePathElement.
     *
     * @param xiParent - the parent, used to check child validity.
     * @param xiEdge   - the edge to encapsulate.
     */
    public void set(TreeNode xiParent, TreeEdge xiEdge)
    {
      mParentRef = xiParent.getRef();
      mEdge      = xiEdge;
      mChildRef  = mEdge.mChildRef;

      assert(getNode(mChildRef) != null) : "Can't add invalid node ref to path";
      assert(xiEdge.numChildVisits <= getNode(mChildRef).numVisits) : "Edge has more visits than child";
    }

    /**
     * Set override scores that are to be fed upward from this element during update propagation, rather than those
     * flowing from lower in the path.
     *
     * @param scores - the scores to use.
     */
    public void setScoreOverrides(TreeNode overridingNode)
    {
      scoreOverrides = new double[mTree.numRoles];

      for (int i = 0; i < mTree.numRoles; i++)
      {
        scoreOverrides[i] = overridingNode.getAverageScore(i);
      }
    }

    /**
     * Retrieve override score vector (if any).
     *
     * @return overrides, or null if there are none.
     */
    public double[] getScoreOverrides()
    {
      return scoreOverrides;
    }

    /**
     * Retrieve the node this path element leads to (downwards).
     *
     * @return the child node, or null if it has been freed.
     */
    public TreeNode getChildNode()
    {
      return getNode(mChildRef);
    }

    /**
     * @return the edge this element encapsulates, or null if the edge has become invalid (because the parent and/or the
     *         child has been freed).
     */
    public TreeEdge getEdge()
    {
      // Check that the edge is still valid before returning it.
      if ((getNode(mParentRef) == null) || (getNode(mChildRef) == null))
      {
        return null;
      }
      return mEdge;
    }

    private TreeNode getNode(long xiNodeRef)
    {
      return TreeNode.get(mTree.nodePool, xiNodeRef);
    }

    /**
     * Reset this tree path element, ready for re-use.
     */
    public void reset()
    {
      mParentRef     = TreeNode.NULL_REF;
      mEdge          = null;
      mChildRef      = TreeNode.NULL_REF;
      scoreOverrides = null; // !! ARR Don't do this.
    }
  }

  /**
   * The tree through which this is a path.
   */
  MCTSTree mTree;

  // The elements that make up the path.  There are very few paths allocated (<50) and they're always recycled, so it's
  // simpler just to ensure this array is big enough for the longest possible path that we'll ever see in any game.
  private final TreePathElement[] mElements    = new TreePathElement[MAX_PATH_LEN];
  private int                     mNumElements = 0;

  // A cursor to an item in the path.  Used for iteration.
  private int mCursor = 0;

  /**
   * Construct a new selection path.
   *
   * @param xiTree - tree the path will be within.
   */
  public TreePath(MCTSTree xiTree)
  {
    reset(xiTree);
    for (int lii = 0; lii < MAX_PATH_LEN; lii++)
    {
      mElements[lii] = new TreePathElement();
    }
  }

  /**
   * Add a new element to the path (building up from root first downwards).
   *
   * @param xiParent - the parent node to add.
   * @param xiEdge   - the selected edge from the parent node.
   *
   * @return the added element.
   */
  public TreePathElement push(TreeNode xiParent, TreeEdge xiEdge)
  {
    TreePathElement lElement = mElements[mNumElements++];
    lElement.set(xiParent, xiEdge);
    mCursor++;
    return lElement;
  }

  /**
   * Reset enumeration cursor to the leaf of the path
   */
  public void resetCursor()
  {
    mCursor = mNumElements;
  }

  /**
   * Are there more elements to be enumerated
   * @return whether there are more
   */
  public boolean hasMore()
  {
    return mCursor > 0;
  }

  /**
   * Advance to the next element during enumeration.  Note that this enumerates from deepest point first up to the root.
   *
   * @return the next node in the path, or null if the path has become invalid at this point (because the node has been
   *         recycled).
   */
  public TreeNode getNextNode()
  {
    mCursor--;
    if (hasMore())
    {
      TreePathElement element = mElements[mCursor - 1];
      TreeNode node = element.getChildNode();
      return node;
    }
    return mTree.root;
  }

  /**
   * @return the current path element during enumeration.
   */
  public TreePathElement getCurrentElement()
  {
    if (mCursor == mNumElements)
    {
      return null;
    }
    return mElements[mCursor];
  }

  /**
   * @return whether any node on the path has been freed.
   */
  public boolean isFreed()
  {
    for (int lii = 0; lii < mNumElements; lii++)
    {
      TreeEdge edge = mElements[lii].getEdge();
      //  In some cases the edge's child can have been freed and re-expanded so the edge
      //  points to a totally different node now than does the child!  This also indicates a
      //  freed path, but is probably a temporary problem which will be resolved when
      //  edges are removed from TreePathElement
      //  Because of edge recycling we also need to check that the edge is still pointing to the
      //  child (if it was recycled while the rollout was in progress it probably will not).
      //  It is even possible that the edge can have been recycled to point back at the SAME
      //  node from a different context - there is no reliable way to spot this and it needs to be
      //  addressed by removing the use of an edge ref in TreePaths altogether (which is intended anyway)
      //  but for now we get the common case which will have a 0 visit count (reuse to point to the same node
      //  with mukltiple visits all wile a rollout takes place is extremely unlikely)
      if ( edge == null ||
           edge.mChildRef == TreeNode.NULL_REF ||
           edge.mChildRef != mElements[lii].mChildRef ||
           edge.numChildVisits == 0 ||
           TreeNode.get(mTree.nodePool, edge.mChildRef) == null )
      {
        return true;
      }
    }

    return false;
  }

  /**
   * Reset the tree path for re-use.
   *
   * @param xiTree - the tree that it will be used in.
   */
  public void reset(MCTSTree xiTree)
  {
    mTree = xiTree;
    for (int lii = 0; lii < mNumElements; lii++)
    {
      mElements[lii].reset();
    }
    mNumElements = 0;
    mCursor = 0;
  }

  /**
   * @return whether the path through the tree is still valid.
   */
  public boolean isValid()
  {
    while(hasMore())
    {
      // Get the edge in the path.  If this returns null, the path has become invalid at this point.
      getNextNode();
      assert(getCurrentElement() != null);

      TreeEdge lEdge = getCurrentElement().getEdge();
      if (lEdge == null)
      {
        return false;
      }

      // The edge can't have been visited more often than its child.  (The converse isn't true because children can
      // have multiple parents.)
      assert(getCurrentElement().getChildNode() != null) : "Child is null even after edge validated";
      assert(lEdge.numChildVisits <= getCurrentElement().getChildNode().numVisits) :
        "Edge " + lEdge + " has been visited " + lEdge.numChildVisits + " times, but the child (" +
        getCurrentElement().getChildNode() + ") only has " + getCurrentElement().getChildNode().numVisits + " visits!";
    }
    resetCursor();
    return true;
  }
}