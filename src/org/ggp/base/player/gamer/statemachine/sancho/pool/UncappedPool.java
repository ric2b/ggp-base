package org.ggp.base.player.gamer.statemachine.sancho.pool;

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
  // Items that are available for re-use.
  private final int                                    mMaxFreeItems;
  private final ItemType[]                             mFreeItems;
  private int                                          mNumFreeItems;

  // Statistical information about pool usage.
  //
  // - The number of items currently is use.
  private int                                          mNumItemsInUse = 0;

  /**
   * Create an pool with no maximum size.  The pool keeps freed objects for re-use, up to the specified maximum.
   *
   * @param xiMaxFreeItems - the number of freed items to keep for re-use.
   */
  @SuppressWarnings("unchecked")
  public UncappedPool(int xiMaxFreeItems)
  {
    mMaxFreeItems = xiMaxFreeItems;
    mFreeItems = (ItemType[])(new Object[xiMaxFreeItems]);
  }

  @Override
  public ItemType allocate(ObjectAllocator<ItemType> xiAllocator) throws GoalDefinitionException
  {
    ItemType lAllocatedItem;

    if (mNumFreeItems > 0)
    {
      // Re-use an item from the free list.
      lAllocatedItem = mFreeItems[--mNumFreeItems];
      xiAllocator.resetObject(lAllocatedItem, false);
    }
    else
    {
      // Allocate a new item.
      lAllocatedItem = xiAllocator.newObject();
    }

    mNumItemsInUse++;
    return lAllocatedItem;
  }

  @Override
  public void free(ItemType xiItem)
  {
    mNumItemsInUse--;
    if (mNumFreeItems < mMaxFreeItems)
    {
      mFreeItems[mNumFreeItems++] = xiItem;
    }
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
