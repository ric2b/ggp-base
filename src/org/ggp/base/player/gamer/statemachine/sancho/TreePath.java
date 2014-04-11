package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.ArrayList;
import java.util.List;

public class TreePath
{
  class TreePathElement
  {
    TreeEdge edge;
    private double[] scoreOverrides = null;

    public TreePathElement(TreeEdge edge)
    {
      this.edge = edge;
    }

    public void setScoreOverrides(double[] scores)
    {
      scoreOverrides = new double[tree.numRoles];

      for (int i = 0; i < tree.numRoles; i++)
      {
        scoreOverrides[i] = scores[i];
      }
    }

    public double[] getScoreOverrides()
    {
      return scoreOverrides;
    }

    public TreeNode getChildNode()
    {
      return edge.child.node;
    }

    public TreeEdge getEdge()
    {
      return edge;
    }
  }

  /**
   *
   */
  private MCTSTree              tree;
  private List<TreePathElement> elements              = new ArrayList<TreePathElement>();
  private int                   index                 = 0;
  public MoveWeightsCollection  propagatedMoveWeights;

  public TreePath(MCTSTree tree)
  {
    this.tree = tree;

    propagatedMoveWeights = new MoveWeightsCollection(tree.numRoles);
  }

  public void push(TreePathElement element)
  {
    elements.add(element);
    index++;
  }

  public int size()
  {
    return elements.size();
  }

  public void resetCursor()
  {
    index = elements.size();
  }

  public boolean hasMore()
  {
    return index > 0;
  }

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

  public TreePathElement getCurrentElement()
  {
    if (index == elements.size())
    {
      return null;
    }
    return elements.get(index);
  }
}