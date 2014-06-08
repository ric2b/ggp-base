
package org.ggp.base.player.gamer.statemachine.sancho.pool;

import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;

/**
 * An pool of items.
 *
 * @param <ItemType> - the type of items stored in this pool.
 */
public interface Pool<ItemType>
{
  /**
   * Interface to be implemented by classes capable of allocating (and resetting) objects in a pool.
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

  /**
   * Allocate a new item from the pool.
   *
   * @param xiAllocator - object allocator to use if no new items are available.
   *
   * @return the new item.
   *
   * @throws GoalDefinitionException
   */
  public ItemType allocate(ObjectAllocator<ItemType> xiAllocator) throws GoalDefinitionException;

  /**
   * Return an item to the pool.
   *
   * The pool promises to call resetObject() for any freed items before re-use.
   *
   * @param xiItem - the item.
   */
  public void free(ItemType xiItem);

  /**
   * @return whether the pool is (nearly) full.
   *
   * When full, the caller needs to free() some items to ensure that subsequently allocations will continue to succeed.
   */
  public boolean isFull();

 /**
   * Clear the pool - resetting all items that are still allocated.
   *
   * @param xiAllocator - an object allocator.
   * @param xiFilter    - whether to filter the items to be reset (used to reset nodes from just one MCTSTree).
   */
  public void clear(ObjectAllocator<ItemType> xiAllocator, boolean xiFilter);

  /**
   * @return the percentage of this pool that is in use.
   */
  public int getPoolUsage();
}
