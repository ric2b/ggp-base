package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.ArrayList;
import java.util.List;

public class TreePath
{
  /**
   * @author steve
   * Individual element in a tree path
   */
  class TreePathElement
  {
    /**
     * Edge being traversed in this path element
     */
    TreeEdge edge;
    private double[] scoreOverrides = null;

    /**
     * Construct a new element suitable for adding to a selection path
     * @param theEdge the edge to encapsulate
     */
    public TreePathElement(TreeEdge theEdge)
    {
      assert(!theEdge.child.node.freed);
      assert(theEdge.selectAs == theEdge);
      assert(theEdge.numChildVisits <= theEdge.child.node.numVisits);

      this.edge = theEdge;
    }

    /**
     * Set override scores that are to be fed upward from this element
     * During update propagation, rather than those flowing from lower
     * in the path
     * @param scores
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
     * Retrieve override score vector (if any)
     * @return overrides, or null if there are none
     */
    public double[] getScoreOverrides()
    {
      return scoreOverrides;
    }

    /**
     * Retrieve the node this path element leads to (downwards)
     * @return child node
     */
    public TreeNode getChildNode()
    {
      return edge.child.node;
    }

    /**
     * Retrieve the edge this element encapsulates
     * @return edge
     */
    public TreeEdge getEdge()
    {
      return edge;
    }
  }

  /**
   *
   */
  MCTSTree                      tree;
  private List<TreePathElement> elements              = new ArrayList<>();
  private int                   index                 = 0;
  public MoveWeightsCollection  propagatedMoveWeights;

  /**
   * Construct a new selection path
   * @param theTree tree the path will be within
   */
  public TreePath(MCTSTree theTree)
  {
    this.tree = theTree;

    propagatedMoveWeights = new MoveWeightsCollection(theTree.numRoles);
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
   * @return number of elements in thepath
   */
  public int size()
  {
    return elements.size();
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
   * Advance to the next element during enumeration.  Note that
   * this enumerates from deepest point first up to the root
   * @return next node
   */
  public TreeNode getNextNode()
  {
    index--;
    if (hasMore())
    {
      TreePathElement element = elements.get(index - 1);
      TreeNode node = element.getChildNode();

      if (node.seq == element.edge.child.seq)
      {
        return node;
      }
      return null;
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
   * Test validity of the path.  Intended for use as the argument to
   * an assert() rather than in normal (assert-disabled) runtime usage
   * @return validity
   */
  public boolean isValid()
  {
    while(hasMore())
    {
      getNextNode();
      assert(getCurrentElement() != null);

      TreeEdge edge = getCurrentElement().getEdge();
      if ( edge != edge.selectAs || edge.child.seq != edge.child.node.seq || edge.numChildVisits > edge.child.node.numVisits)
      {
        assert(false);
        return false;
      }
    }
    resetCursor();
    return true;
  }
}