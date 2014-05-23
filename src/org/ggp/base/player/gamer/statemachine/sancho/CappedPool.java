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
  private final int                                    mTableSize;

  // The pool of items.
  private final ItemType[]                             mItems;

  // List of items that are free to be re-used.
  private List<ItemType>                               mFreeList         = new LinkedList<>();

  // Array index of the largest allocated item.  Used to track whether an attempt to allocate a new item should really
  // allocate a new item (if we're not yet at the maximum) or re-use and existing item.  This can never exceed
  // mTableSize.
  private int                                          mLargestUsedIndex = -1;

  // The sequence number to assign to the next allocated item.  Every call to allocate() results in a unique sequence
  // number, even (or especially) when a item is being re-used.  This, in combination with TreeNodeRef, allows the
  // calling code to lazily tidy up references to items, whilst still permitting their re-use in the mean time.
  private int                                          mNextSeq          = 0;

  // Statistical information about pool usage.
  //
  // - The number of items that are currently in use.
  // - The number of times that items have been returned to the pool.
  private int                                          mNumUsedItems     = 0;
  private int                                          mNumFreedItems    = 0;

  /**
   * Create a new pool of the specified maximum size.
   *
   * @param tableSize - the pool size.
   */
  @SuppressWarnings("unchecked")
  public CappedPool(int tableSize)
  {
    mTableSize = tableSize;
    mItems = (ItemType[])(new Object[tableSize]);
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
  public int getNumUsedItems()
  {
    return mNumUsedItems;
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
   * @return the number of times that free() has been called for this pool.
   */
  public int getNumFreedItems()
  {
    return mNumFreedItems;
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

    if (mLargestUsedIndex < mTableSize - 1)
    {
      // If we haven't allocated the maximum number of items yet, just allocate another.
      lAllocatedItem = xiAllocator.newObject(mNextSeq++);
      mItems[++mLargestUsedIndex] = lAllocatedItem;
    }
    else
    {
      // We've allocated the maximum number of items, so grab one from the freed list.
      assert(!mFreeList.isEmpty()) : "Unexpectedly full transition table";
      lAllocatedItem = mFreeList.remove(0);

      // Reset the item so that it's ready for re-use.
      xiAllocator.resetObject(lAllocatedItem, false, mNextSeq++);
    }

    mNumUsedItems++;
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
    mNumFreedItems++;
    mNumUsedItems--;
    mFreeList.add(xiItem);
  }

  /**
   * @return whether the pool is (nearly) full.
   *
   * When full, the caller needs to free() some items to ensure that subsequently allocations will continue to succeed.
   */
  public boolean isFull()
  {
    return (mNumUsedItems > mTableSize - 200);
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

      mNumUsedItems = 0;
      mNumFreedItems = 0;
      mNumFreedItems = 0;
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
          mNumUsedItems--;
          // We don't increment numFreedItems here since what it is (intentionally) measuring and reporting is how
          // much forced trimming is going on.
        }
      }
    }
  }
}
