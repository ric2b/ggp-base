package org.ggp.base.player.gamer.statemachine.sancho;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A garbage-free queue of node references.
 */
public class NodeRefQueue
{
  private static final Logger LOGGER = LogManager.getLogger();

  private final long[] mBuffer;
  private final int mCapacity;

  private int mNextInsertIndex;
  private int mNextRemoveIndex;
  private int mSize; // Number of items currently in the queue.

  /**
   * Create a queue of node references with the specified maximum capacity.
   *
   * @param xiCapacity - the capacity.
   */
  public NodeRefQueue(int xiCapacity)
  {
    mCapacity = xiCapacity;
    mBuffer = new long[mCapacity];
    mNextInsertIndex = 0;
    mNextRemoveIndex = 0;
    mSize = 0;

    // For ease of debugging, set all empty slots to NULL_REF.
    for (int lii = 0; lii < mCapacity; lii++)
    {
      mBuffer[lii] = TreeNode.NULL_REF;
    }
  }

  /**
   * Add a node reference to the tail of the queue.
   *
   * @param xiRef - the node reference.
   */
  public void add(long xiRef)
  {
    assert(xiRef != TreeNode.NULL_REF) : "Not allowed to queue null refs";

    if (mSize == mCapacity)
    {
      LOGGER.warn("Not inserting node ref " + xiRef + " because the queue is full");
      assert(mNextInsertIndex == mNextRemoveIndex) :
                  "Queue is full (" + mSize + ") but has mHead = " + mNextInsertIndex + ", mTail = " + mNextRemoveIndex;
      return;
    }

    mBuffer[mNextInsertIndex] = xiRef;
    mNextInsertIndex = (mNextInsertIndex + 1) % mCapacity;
    mSize++;
  }

  /**
   * Remove the node reference at the head of the queue.
   *
   * @return the node reference.
   */
  public long remove()
  {
    assert(mSize != 0);

    long lRef = mBuffer[mNextRemoveIndex];
    assert(lRef != TreeNode.NULL_REF);

    mBuffer[mNextRemoveIndex] = TreeNode.NULL_REF; // Clear empty slots for ease of debugging.

    mNextRemoveIndex = (mNextRemoveIndex + 1) % mCapacity;
    mSize--;

    return lRef;
  }

  /**
   * @return the number of elements in the queue.
   */
  public int size()
  {
    return mSize;
  }

  /**
   * @return whether the queue is empty.
   */
  public boolean isEmpty()
  {
    return mSize == 0;
  }

  /**
   * @return whether the queue contains the specified node reference.
   *
   * @param xiRef - the node reference.
   */
  public boolean contains(long xiRef)
  {
    for (int lIndex = mNextRemoveIndex, mToCheck = mSize;
         mToCheck > 0;
         mToCheck--)
    {
      if (mBuffer[lIndex] == xiRef)
      {
        return true;
      }
    }
    return false;
  }
}
