package org.ggp.base.player.gamer.statemachine.sancho;

import com.lmax.disruptor.Sequence;

/**
 * Highly efficient, lock-free rollout request pipeline with a single consumer.
 */
public class SimplePipeline
{
  /**
   * Maximum size of the pipeline and mask for computing index into the underlying array.
   */
  private final int mSize;
  private final int mIndexMask;

  /**
   * The array holding the requests.
   */
  private final RolloutRequest[] mStore;

  /**
   * The last request which has been published following select/expand processing.
   *
   *  Written by: Tree thread.
   *  Read by:    Tree thread, Rollout thread.
   */
  private final Sequence mLastExpanded = new Sequence();
  private long mLastExpandedCache = -1;

  /**
   * The last request which has been rolled out.
   *
   * Written by: Rollout thread.
   * Read by:    Tree thread.
   */
  private final Sequence mLastRolledOut = new Sequence();
  private long mLastRolledOutCache = -1;

  /**
   * The last request which has been completed by the back-propagation processing.
   *
   * Written by: Tree thread.
   * Read by:    Tree thread.
   */
  private long mLastBackPropagated = -1L;

  /**
   * Whether the pipeline has been halted.
   */
  private volatile boolean mPipelineHalted = false;

  /**
   * Create a pipeline with the specified maximum size.
   *
   * @param xiSize - the maximum number of objects that can be in the pipeline.
   * @param xiNumRoles number of roles in the game
   */
  public SimplePipeline(int xiSize, int xiNumRoles)
  {
    assert(Integer.bitCount(xiSize) == 1) : "Store size must be a power of 2 and > 0";

    mSize = xiSize;
    mIndexMask = xiSize - 1;

    // Create the backing store.
    mStore = new RolloutRequest[mSize];
    for (int lii = 0; lii < mSize; lii++)
    {
      mStore[lii] = new RolloutRequest(xiNumRoles);
    }
  }

  /**
   * @return whether the tree thread can perform an expansion now.
   */
  public boolean canExpand()
  {
    return (mLastExpandedCache < mLastBackPropagated + mSize);
  }

  /**
   * @return a blank rollout request to be filled in.
   *
   * The caller must ensure (or know) that {@link #canExpand()} is true before calling this method.  After calling
   * this method, the caller must call {@link #expandComplete()}.
   */
  public RolloutRequest getNextExpandSlot()
  {
    assert(canExpand()) : "Ensure canExpand() before calling getNextExpandSlot()";
    return mStore[(int)(mLastExpandedCache + 1) & mIndexMask];
  }

  /**
   * Publish a rollout request for further processing.
   */
  public void expandComplete()
  {
    assert(canExpand()) : "Call getNextExpandSlot() before calling expandComplete()";
    mLastExpandedCache++;
    mLastExpanded.setVolatile(mLastExpandedCache);
  }

  /**
   * Get the next rollout request for the specified thread.
   *
   * This method will not return until a rollout request is available.
   *
   * @param xiThreadIndex - the thread making the request.
   *
   * @return the next rollout request.
   */
  public RolloutRequest getNextRolloutRequest()
  {
    final long lNextRequestID = mLastRolledOutCache + 1;

    // Try a non-volatile read first, for speed.
    if (mLastExpandedCache >= lNextRequestID)
    {
      return mStore[(int)lNextRequestID & mIndexMask];
    }

    final Thread lThread = Thread.currentThread();

    // Perform volatile reads until the next request is available.  (The cached value will be automatically updated
    // because get() is a volatile read, which fetches everything that happened before, including updating the cache
    // value.)
    while ((!lThread.isInterrupted()) && (mLastExpanded.get() < lNextRequestID))
    {
      Thread.yield();
    }

    return mStore[(int)lNextRequestID & mIndexMask];
  }

  /**
   * Mark a rollout as complete.
   */
  public void rolloutComplete()
  {
    mLastRolledOutCache++;
    mLastRolledOut.setVolatile(mLastRolledOutCache);
  }

  /**
   * @return whether there are any completed rollout requests ready for back-propagation.
   */
  public boolean canBackPropagate()
  {
    // See if we already know that back-propagation will be successful.
    if (mLastRolledOutCache > mLastBackPropagated)
    {
      return true;
    }

    if (isEmpty())
    {
      return false;
    }

    return (mLastRolledOut.get() > mLastBackPropagated);
  }

  /**
   * @return the next rollout request to be back-propagated.
   *
   * This method will not return until a rollout request is available.
   */
  public RolloutRequest getNextRequestForBackPropagation()
  {
    final long lNextRequestID = mLastBackPropagated + 1;

    // See if we already know that a request is available.
    if (mLastRolledOutCache >= lNextRequestID)
    {
      return mStore[(int)lNextRequestID & mIndexMask];
    }

    // Perform volatile reads until the next request is available.
    while ((mLastRolledOutCache = mLastRolledOut.get()) < lNextRequestID)
    {
      Thread.yield();
    }

    return mStore[(int)lNextRequestID & mIndexMask];
  }

  /**
   * Mark that back-propagation of a request is complete.
   */
  public void backPropagationComplete()
  {
    mLastBackPropagated++;
  }

  /**
   * @return whether the pipeline is completely empty.
   */
  public boolean isEmpty()
  {
    return mLastExpandedCache == mLastBackPropagated;
  }
}
