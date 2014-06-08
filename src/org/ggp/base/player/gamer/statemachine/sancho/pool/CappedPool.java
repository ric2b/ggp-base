package org.ggp.base.player.gamer.statemachine.sancho.pool;

import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;

/**
 * A pool with a fixed maximum size.
 *
 * Freed items are kept in the pool to avoid excessive object allocation.
 *
 * @param <ItemType> the type of item to be kept in the pool.
 */
public class CappedPool<ItemType> implements Pool<ItemType>
{
  /**
   * A dummy sequence number for unallocated pool items.
   */
  public static final int NULL_ITEM_SEQ = -1;

  /**
   * A sequence number for pool items that have been freed.
   */
  public static final int FREED_ITEM_SEQ = -2;

  // Maximum number of items to allocate.
  private final int                                    mPoolSize;

  // The pool of items.
  private final ItemType[]                             mItems;

  // Items that are available for re-use.
  private final ItemType[]                             mFreeItems;
  private int                                          mNumFreeItems;

  // Array index of the largest allocated item.  Used to track whether an attempt to allocate a new item should really
  // allocate a new item (if we're not yet at the maximum) or re-use and existing item.  This can never exceed
  // mPoolSize.
  private int                                          mLargestUsedIndex = -1;

  // The sequence number to assign to the next allocated item.  Every call to allocate() results in a unique sequence
  // number, even (or especially) when a item is being re-used.  This, in combination with TreeNodeRef, allows the
  // calling code to lazily tidy up references to items, whilst still permitting their re-use in the mean time.
  private int                                          mNextSeq          = 0;

  // Statistical information about pool usage.
  //
  // - The number of items currently is use.
  private int                                          mNumItemsInUse = 0;

  /**
   * Create a new pool of the specified maximum size.
   *
   * @param xiPoolSize - the pool size.
   */
  @SuppressWarnings("unchecked")
  public CappedPool(int xiPoolSize)
  {
    mPoolSize  = xiPoolSize;
    mItems     = (ItemType[])(new Object[xiPoolSize]);
    mFreeItems = (ItemType[])(new Object[xiPoolSize]);
  }

  /**
   * @return the table of items that backs this pool.
   *
   * This is a hack which is only used for MCTSTree validation.
   */
  public ItemType[] getItemTable()
  {
    return mItems;
  }

  /**
   * @return the number of items currently in active use.
   */
  public int getNumItemsInUse()
  {
    return mNumItemsInUse;
  }

  @Override
  public int getPoolUsage()
  {
    return mNumItemsInUse * 100 / mPoolSize;
  }

  /**
   * Get the age of an object with specified seq
   * @param seq of the object whose age is being queried
   * @return age in number of allocations
   */
  public int getAge(int seq)
  {
    return mNextSeq - seq;
  }

  @Override
  public ItemType allocate(ObjectAllocator<ItemType> xiAllocator) throws GoalDefinitionException
  {
    ItemType lAllocatedItem;

    if (mLargestUsedIndex < mPoolSize - 1)
    {
      // If we haven't allocated the maximum number of items yet, just allocate another.
      lAllocatedItem = xiAllocator.newObject(mNextSeq++);
      mItems[++mLargestUsedIndex] = lAllocatedItem;
    }
    else
    {
      // We've allocated the maximum number of items, so grab one from the freed list.
      assert(mNumFreeItems != 0) : "Unexpectedly full pool";
      lAllocatedItem = mFreeItems[--mNumFreeItems];

      // Reset the item so that it's ready for re-use.
      xiAllocator.resetObject(lAllocatedItem, false, mNextSeq++);
    }

    mNumItemsInUse++;
    return lAllocatedItem;
  }

  @Override
  public void free(ItemType xiItem)
  {
    mNumItemsInUse--;
    mFreeItems[mNumFreeItems++] = xiItem;
  }

  @Override
  public boolean isFull()
  {
    return (mNumItemsInUse > mPoolSize - 200);
  }

  @Override
  public void clear(ObjectAllocator<ItemType> xiAllocator, boolean xiFilter)
  {
    if (!xiFilter)
    {
      // Reset every allocated object and add it to the free list.
      for (int i = 0; i <= mLargestUsedIndex; i++)
      {
        xiAllocator.resetObject(mItems[i], true, NULL_ITEM_SEQ);
        mFreeItems[i] = mItems[i];
      }

      mNumFreeItems = mLargestUsedIndex + 1;
      mNumItemsInUse = 0;
      mNextSeq = 0;
    }
    else
    {
      for (int i = 0; i <= mLargestUsedIndex; i++)
      {
        // Just reset the items that match the filter.
        if (xiAllocator.shouldReset(mItems[i]))
        {
          xiAllocator.resetObject(mItems[i], true, NULL_ITEM_SEQ);
          mFreeItems[mNumFreeItems++] = mItems[i];
          mNumItemsInUse--;
        }
      }
    }
  }
}
