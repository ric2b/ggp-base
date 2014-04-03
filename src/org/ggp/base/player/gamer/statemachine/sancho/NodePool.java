package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.LinkedList;
import java.util.List;

import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;

public class NodePool
{
  private TreeNode[]                                   nodeTable                          = null;
  List<TreeNode>                                       freeList                           = new LinkedList<>();
  private int                                          largestUsedIndex                   = -1;
  private int                                          nextSeq                            = 0;
  private int                                          nodeTableSize;
  private int                                          numFreedNodes                      = 0;
  private int                                          numUsedNodes                       = 0;

  public NodePool(int tableSize)
  {
    nodeTableSize = tableSize;
    nodeTable = new TreeNode[tableSize];
  }

  public TreeNode[] getNodesTable()
  {
    return nodeTable;
  }

  public int getNumUsedNodes()
  {
    return numUsedNodes;
  }

  public int getNumFreedNodes()
  {
    return numFreedNodes;
  }

  public TreeNode allocateNode(MCTSTree tree)
      throws GoalDefinitionException
  {
    TreeNode result;

    //System.out.println("Add state " + state);
    if (largestUsedIndex < nodeTableSize - 1)
    {
      result = new TreeNode(tree, tree.numRoles);
      nodeTable[++largestUsedIndex] = result;
    }
    else if (!freeList.isEmpty())
    {
      result = freeList.remove(0);

      if (!result.freed)
      {
        System.out.println("Bad allocation choice");
      }

      result.reset(tree);
    }
    else
    {
      throw new RuntimeException("Unexpectedly full transition table");
    }

    numUsedNodes++;
    result.seq = nextSeq++;
    return result;
  }

  public void free(TreeNode node)
  {
    numFreedNodes++;
    node.seq = -2; //  Must be negative and distinct from -1, the null ref seq value
    node.freed = true;

    numUsedNodes--;
    freeList.add(node);
  }

  public void clear(MCTSTree tree)
  {
    if ( tree == null )
    {
      freeList.clear();
      for (int i = 0; i <= largestUsedIndex; i++)
      {
        nodeTable[i].reset(null);
        freeList.add(nodeTable[i]);
      }

      numUsedNodes = 0;
      numFreedNodes = 0;
      numFreedNodes = 0;
    }
    else
    {
      for (int i = 0; i <= largestUsedIndex; i++)
      {
        if ( nodeTable[i].tree == tree && !nodeTable[i].freed )
        {
          nodeTable[i].reset(null);
          freeList.add(nodeTable[i]);
          numUsedNodes--;
          //  We don't increment numFreedNodes here since what it is (intention)
          //  measuring and reporting is how much forced trimming is going on
        }
      }
    }
  }

  public boolean isFull()
  {
    return (numUsedNodes > nodeTableSize - 200);
  }
}
