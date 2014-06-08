package org.ggp.base.player.gamer.statemachine.sancho.pool;

import java.util.LinkedList;
import java.util.List;

import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;

/**
 * A pool with no maximum size.
 *
 * Freed items are kept in the pool to avoid excessive object allocation.
 *
 * @param <ItemType> the type of item to be kept in the pool.
 */
public class UncappedPool<ItemType> implements Pool<ItemType>
{
  /**
   * A dummy sequence number for unallocated pool items.
   */
  public static final int NULL_ITEM_SEQ = -1;

  /**
   * A sequence number for pool items that have been freed.
   */
  public static final int FREED_ITEM_SEQ = -2;

  // List of items that are free to be re-used.
  private List<ItemType>                               mFreeList         = new LinkedList<>();

  // Statistical information about pool usage.
  //
  // - The number of items currently is use.
  private int                                          mNumItemsInUse = 0;

  @Override
  public ItemType allocate(ObjectAllocator<ItemType> xiAllocator) throws GoalDefinitionException
  {
    ItemType lAllocatedItem;

    if (!mFreeList.isEmpty())
    {
      // Re-use an item from the free list.
      lAllocatedItem = mFreeList.remove(0);
    }
    else
    {
      // Allocate a new item.
      lAllocatedItem = xiAllocator.newObject(0);
    }

    mNumItemsInUse++;
    return lAllocatedItem;
  }

  @Override
  public void free(ItemType xiItem)
  {
    mNumItemsInUse--;
    mFreeList.add(xiItem);
  }

  @Override
  public boolean isFull()
  {
    return false;
  }

  @Override
  public int getPoolUsage()
  {
    // This pool has no maximum size, so is always 0% full.
    return 0;
  }

  @Override
  public void clear(ObjectAllocator<ItemType> xiAllocator, boolean xiFilter)
  {
    // This pool doesn't keep references to the items it has allocated, so it can't free them all.
    assert(false) : "Don't call clear() on UncappedPool";
  }
}
