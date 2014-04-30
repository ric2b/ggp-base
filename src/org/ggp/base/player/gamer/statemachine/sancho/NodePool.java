package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.LinkedList;
import java.util.List;

import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;

/**
 * A pool of tree nodes with a fixed maximum size.
 *
 * Freed nodes are kept in the pool to avoid excessive object allocation.
 */
public class NodePool
{
  // Maximum number of nodes to allocate.
  private final int                                    nodeTableSize;

  // The pool of nodes.
  private TreeNode[]                                   nodeTable                          = null;

  // List of nodes that are free to be re-used.
  private List<TreeNode>                               freeList                           = new LinkedList<>();

  // Array index of the largest allocated node.  Used to track whether an attempt to allocate a new node should really
  // allocate a new node (if we're not yet at the maximum) or re-use and existing node.  This can never exceed
  // nodeTableSize.
  private int                                          largestUsedIndex                   = -1;

  // The sequence number to assign to the next allocated node.  Every call to allocateNode() results in a unique
  // sequence number, even (or especially) when a node is being re-used.  This, in combination with TreeNodeRef, allows
  // the calling code to lazily tidy up references nodes, whilst still permitting their re-use in the mean time.
  private int                                          nextSeq                            = 0;

  // Statistical information about pool usage.
  //
  // - The number of nodes that are currently in use.
  // - The number of times that nodes have been returned to the pool.
  private int                                          numUsedNodes                       = 0;
  private int                                          numFreedNodes                      = 0;

  /**
   * Create a new node pool of the specified maximum size.
   *
   * @param tableSize - the pool size.
   */
  public NodePool(int tableSize)
  {
    nodeTableSize = tableSize;
    nodeTable = new TreeNode[tableSize];
  }

  /**
   * @return the table of nodes that backs this pool.
   *
   * This is a hack which is only used for MCTSTree validation.
   */
  public TreeNode[] getNodesTable()
  {
    return nodeTable;
  }

  /**
   * @return the number of nodes currently in active use.
   */
  public int getNumUsedNodes()
  {
    return numUsedNodes;
  }

  /**
   * @return the number of times that free() has been called for this pool.
   */
  public int getNumFreedNodes()
  {
    return numFreedNodes;
  }

  /**
   * Allocate a new node from the pool.
   *
   * @param tree - the tree in which the node should be allocated.
   * !! ARR Hack - it shouldn't need to know this.  MCTSTree should implement TreeNodeAllocator and we should use that.
   * !! ARR Better still, this class should use generics.
   *
   * @return the new node.
   *
   * @throws GoalDefinitionException
   */
  public TreeNode allocateNode(MCTSTree tree) throws GoalDefinitionException
  {
    TreeNode lAllocatedNode;

    if (largestUsedIndex < nodeTableSize - 1)
    {
      // If we haven't allocated the maximum number of nodes yet, just allocate another.
      lAllocatedNode = new TreeNode(tree, tree.numRoles);
      nodeTable[++largestUsedIndex] = lAllocatedNode;
    }
    else
    {
      // We've allocated the maximum number of nodes, so grab one from the freed list.
      assert(!freeList.isEmpty()) : "Unexpectedly full transition table";
      lAllocatedNode = freeList.remove(0);

      assert(lAllocatedNode.freed) : "Bad node allocation choice: " + lAllocatedNode;
      // !! ARR NodePool encapsulation

      // Reset the node so that it's ready for re-use.
      lAllocatedNode.reset(tree);
      // !! ARR NodePool encapsulation
    }

    numUsedNodes++;
    lAllocatedNode.seq = nextSeq++;
    return lAllocatedNode;
  }

  /**
   * Return a node to the pool.
   *
   * The pool promises to reset() any freed nodes before re-use.
   *
   * @param node - the node.
   */
  public void free(TreeNode node)
  {
    numFreedNodes++;
    numUsedNodes--;
    freeList.add(node);
  }

  /**
   * @return whether the pool is (nearly) full.
   *
   * When full, the caller needs to free() some nodes to ensure that subsequently allocations will continue to succeed.
   */
  public boolean isFull()
  {
    return (numUsedNodes > nodeTableSize - 200);
  }

  /**
   * Clear the node pool - freeing all nodes that are still allocated.
   *
   * @param tree - null to free all nodes in the pool, or an MCTSTree if only nodes in the specified tree should be
   * freed.
   */
  public void clear(MCTSTree tree)
  {
    if (tree == null)
    {
      freeList.clear();
      for (int i = 0; i <= largestUsedIndex; i++)
      {
        nodeTable[i].reset(null);
        // !! ARR NodePool encapsulation
        freeList.add(nodeTable[i]);
      }

      numUsedNodes = 0;
      numFreedNodes = 0;
      numFreedNodes = 0;
      nextSeq = 0;
    }
    else
    {
      for (int i = 0; i <= largestUsedIndex; i++)
      {
        if ((nodeTable[i].tree == tree) && (!nodeTable[i].freed))
        {
          nodeTable[i].reset(null);
          // !! ARR NodePool encapsulation
          freeList.add(nodeTable[i]);
          numUsedNodes--;
          //  We don't increment numFreedNodes here since what it is (intentionally) measuring and reporting is how
          // much forced trimming is going on.
        }
      }
    }
  }
}
