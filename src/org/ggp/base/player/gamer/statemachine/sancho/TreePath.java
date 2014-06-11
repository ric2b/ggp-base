package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Path through an MCTS tree.
 */
public class TreePath
{
  static final Logger LOGGER       = LogManager.getLogger();

  /**
   * Individual element in a tree path
   */
  class TreePathElement
  {
    // The parent node, the edge leading from it and the child.  It is only valid to access the edge if both node
    // references are still valid.
    private final long     mParentRef;
    private final TreeEdge mEdge;
    private final long     mChildRef;

    // Score overrides to use above this point in the path.
    private double[] scoreOverrides = null;

    /**
     * Construct a new element suitable for adding to a selection path
     *
     * @param xiParent - the parent, used to check child validity.
     * @param theEdge  - the edge to encapsulate.
     */
    public TreePathElement(TreeNode xiParent, TreeEdge xiEdge)
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
    public void setScoreOverrides(double[] scores)
    {
      scoreOverrides = new double[tree.numRoles];

      for (int i = 0; i < tree.numRoles; i++)
      {
        scoreOverrides[i] = scores[i];
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
      return TreeNode.get(tree.nodePool, xiNodeRef);
    }
  }

  /**
   *
   */
  MCTSTree                      tree;
  private List<TreePathElement> elements              = new ArrayList<>();
  private int                   index                 = 0;

  /**
   * Construct a new selection path
   * @param theTree tree the path will be within
   */
  public TreePath(MCTSTree theTree)
  {
    this.tree = theTree;
  }

  /**
   * Add a new element to the path (building up from root first downwards)
   * @param element new element to add
   */
  public void push(TreePathElement element)
  {
    elements.add(element);
    index++;
  }

  /**
   * Reset enumeration cursor to the leaf of the path
   */
  public void resetCursor()
  {
    index = elements.size();
  }

  /**
   * Are there more elements to be enumerated
   * @return whether there are more
   */
  public boolean hasMore()
  {
    return index > 0;
  }

  /**
   * Advance to the next element during enumeration.  Note that this enumerates from deepest point first up to the root.
   *
   * @return the next node in the path, or null if the path has become invalid at this point (because the node has been
   *         recycled).
   */
  public TreeNode getNextNode()
  {
    index--;
    if (hasMore())
    {
      TreePathElement element = elements.get(index - 1);
      TreeNode node = element.getChildNode();
      return node;
    }
    return tree.root;
  }

  /**
   * Get the current path element during enumeration
   * @return current element
   */
  public TreePathElement getCurrentElement()
  {
    if (index == elements.size())
    {
      return null;
    }
    return elements.get(index);
  }

  /**
   * Determine if any node on the path has been freed
   * @return whether any traversed node is freed
   */
  public boolean isFreed()
  {
    for(TreePathElement element : elements)
    {
      TreeEdge edge = element.getEdge();
      //  In some cases the edge's child can have been freed and re-expanded so the edge
      //  points to a totally different node now than does the child!  This also indicates a
      //  freed path, but is probably a temporary problem which will be resolved when
      //  edges are removed from TreePathElement
      if ( edge == null || TreeNode.get(tree.nodePool, edge.mChildRef) != null )
      {
        return true;
      }
    }

    return false;
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