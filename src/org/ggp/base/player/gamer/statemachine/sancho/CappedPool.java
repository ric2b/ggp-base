package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.LinkedList;
import java.util.List;

import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;

/**
 * A pool with a fixed maximum size.
 *
 * Freed items are kept in the pool to avoid excessive object allocation.
 *
 * @param <ItemType> the type of item to be kept in the pool.
 */
public class CappedPool<ItemType>
{
  /**
   * A dummy sequence number for unallocated pool items.
   */
  public static final int NULL_ITEM_SEQ = -1;

  /**
   * A sequence number for pool items that have been freed.
   */
  public static final int FREED_ITEM_SEQ = -2;

  /**
   * Interface to be implemented by classes capable of allocating (and resetting) objects in a capped pool.
   *
   * @param <ItemType> the type of item to be allocated.
   */
  public interface ObjectAllocator<ItemType>
  {
    /**
     * @return a newly allocated object.
     *
     * @param xiSeq - the sequence number for the object.
     *
     * @throws GoalDefinitionException if the object couldn't be allocated.
     */
    public ItemType newObject(int xiSeq) throws GoalDefinitionException; // !! ARR Use a more generic exception

    /**
     * Reset an object, ready for re-use.
     *
     * @param xiObject - the object to reset.
     * @param xiFree   - whether to free the internals.
     * @param xiSeq    - the sequence number for the object.
     */
    public void resetObject(ItemType xiObject, boolean xiFree, int xiSeq);

    /**
     *
     * @param xiObject - the object
     * @return whether an object should be reset.
     */
    public boolean shouldReset(ItemType xiObject);
  }

  // Maximum number of items to allocate.
  private final int                                    mPoolSize;

  // The pool of items.
  private final ItemType[]                             mItems;

  // List of items that are free to be re-used.
  private List<ItemType>                               mFreeList         = new LinkedList<>();

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
    mPoolSize = xiPoolSize;
    mItems = (ItemType[])(new Object[xiPoolSize]);
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

  /**
   * @return the percentage of this pool that is in use.
   */
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

  /**
   * Allocate a new item from the pool.
   *
   * @param xiAllocator - object allocator to use if no new items are available.
   *
   * @return the new item.
   *
   * @throws GoalDefinitionException
   */
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
      assert(!mFreeList.isEmpty()) : "Unexpectedly full pool";
      lAllocatedItem = mFreeList.remove(0);

      // Reset the item so that it's ready for re-use.
      xiAllocator.resetObject(lAllocatedItem, false, mNextSeq++);
    }

    mNumItemsInUse++;
    return lAllocatedItem;
  }

  /**
   * Return an item to the pool.
   *
   * The pool promises to call resetObject() for any freed items before re-use.
   *
   * @param xiItem - the item.
   */
  public void free(ItemType xiItem)
  {
    mNumItemsInUse--;
    mFreeList.add(xiItem);
  }

  /**
   * @return whether the pool is (nearly) full.
   *
   * When full, the caller needs to free() some items to ensure that subsequently allocations will continue to succeed.
   */
  public boolean isFull()
  {
    return (mNumItemsInUse > mPoolSize - 200);
  }

  /**
   * Clear the pool - resetting all items that are still allocated.
   *
   * @param xiAllocator - an object allocator.
   * @param xiFilter    - whether to filter the items to be reset (used to reset nodes from just one MCTSTree).
   */
  public void clear(ObjectAllocator<ItemType> xiAllocator, boolean xiFilter)
  {
    if (!xiFilter)
    {
      // Remove everything from the free list (because we're about to add it all back)
      mFreeList.clear();

      // Reset every allocated object, freeing off internally allocated memory.
      for (int i = 0; i <= mLargestUsedIndex; i++)
      {
        xiAllocator.resetObject(mItems[i], true, NULL_ITEM_SEQ);
        mFreeList.add(mItems[i]);
      }

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
          mFreeList.add(mItems[i]);
          mNumItemsInUse--;
        }
      }
    }
  }
}
